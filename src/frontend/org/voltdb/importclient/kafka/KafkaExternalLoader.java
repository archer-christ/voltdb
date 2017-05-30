/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.importclient.kafka;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.zookeeper_voltpatches.ZooKeeper;
import org.voltcore.logging.Level;
import org.voltcore.logging.VoltLogger;
import org.voltdb.CLIConfig;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientImpl;
import org.voltdb.client.ClientResponse;
import org.voltdb.importclient.kafka.KafkaStreamImporterConfig.HostAndPort;
import org.voltdb.importer.ImporterSupport;
import org.voltdb.importer.formatter.AbstractFormatterFactory;
import org.voltdb.importer.formatter.Formatter;
import org.voltdb.importer.formatter.FormatterBuilder;
import org.voltdb.importer.formatter.builtin.VoltCSVFormatterFactory;
import org.voltdb.utils.BulkLoaderErrorHandler;
import org.voltdb.utils.CSVBulkDataLoader;
import org.voltdb.utils.CSVDataLoader;
import org.voltdb.utils.CSVTupleDataLoader;
import org.voltdb.utils.RowWithMetaData;

import kafka.cluster.Broker;

/**
 * Import Kafka data into the database, using a remote Volt client and manual offset management.
 */
public class KafkaExternalLoader implements ImporterSupport {

    private static final VoltLogger m_log = new VoltLogger("KAFKALOADER2");
    private static final int LOG_SUPPRESSION_INTERVAL_SECONDS = 60;

    private final KafkaExternalLoaderCLIArguments m_config;
    private final static AtomicLong m_failedCount = new AtomicLong(0);

    private CSVDataLoader m_loader = null;
    private Client m_client = null;
    private ExecutorService m_executorService = null;

    public KafkaExternalLoader(KafkaExternalLoaderCLIArguments config) {
        m_config = config;
    }

    public void closeConsumer() throws InterruptedException {

        stop();
        if (m_executorService != null) {
            m_executorService.shutdownNow();
            m_executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            m_executorService = null;
        }
    }

    /**
     * Close all connections and cleanup on both the sides.
     */
    public void close() {
        try {
            closeConsumer();
            m_loader.close();
            if (m_client != null) {
                m_client.close();
                m_client = null;
            }
        } catch (Exception ex) {
        }
    }

    private void processKafkaMessages() throws Exception {

        // If we need to prompt the user for a VoltDB password, do so.
        m_config.password = CLIConfig.readPasswordIfNeeded(m_config.user, m_config.password, "Enter password: ");

        // Create connection
        final ClientConfig c_config = new ClientConfig(m_config.user, m_config.password, null);
        if (m_config.ssl != null && !m_config.ssl.trim().isEmpty()) {
            c_config.setTrustStoreConfigFromPropertyFile(m_config.ssl);
            c_config.enableSSL();
        }
        c_config.setProcedureCallTimeout(0); // Set procedure all to infinite

        // Create the Volt client:
        final String[] voltServers = m_config.servers.split(",");
        m_client = getVoltClient(c_config, voltServers, m_config.port);

        if (m_config.useSuppliedProcedure) {
            m_loader = new CSVTupleDataLoader((ClientImpl) m_client, m_config.procedure, new KafkaBulkLoaderCallback());
        } else {
            m_loader = new CSVBulkDataLoader((ClientImpl) m_client, m_config.table, m_config.batch, m_config.update, new KafkaBulkLoaderCallback());
        }
        m_loader.setFlushInterval(m_config.flush, m_config.flush);

        try {

            m_executorService = getConsumerExecutor(m_loader, this);
            if (m_config.useSuppliedProcedure) {
                m_log.info("Kafka Consumer from topic: " + m_config.topic + " Started using procedure: " + m_config.procedure);
            } else {
                m_log.info("Kafka Consumer from topic: " + m_config.topic + " Started for table: " + m_config.table);
            }

            // Wait forever, per http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/package-summary.htm
            m_executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        catch (Throwable terminate) {
            m_log.error("Error in Kafka Consumer", terminate);
            System.exit(-1);
        }
        close();
    }

    class KafkaBulkLoaderCallback implements BulkLoaderErrorHandler {

        @Override
        public boolean handleError(RowWithMetaData metaData, ClientResponse response, String error) {
            if (m_config.maxerrors <= 0) return false;
            if (response != null) {
                byte status = response.getStatus();
                if (status != ClientResponse.SUCCESS) {
                    m_log.error("Failed to Insert Row: " + metaData.rawLine);
                    long fc = m_failedCount.incrementAndGet();
                    if ((m_config.maxerrors > 0 && fc > m_config.maxerrors)
                           || (status != ClientResponse.USER_ABORT && status != ClientResponse.GRACEFUL_FAILURE)) {
                        try {
                            m_log.error("Kafkaloader will exit.");
                            closeConsumer();
                            return true;
                        }
                        catch (InterruptedException ex) {
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public boolean hasReachedErrorLimit() {
            long fc = m_failedCount.get();
            return (m_config.maxerrors > 0 && fc > m_config.maxerrors);
        }
    }


    private volatile boolean m_stopping = false;

    @Override
    public boolean shouldRun() {
        return !m_stopping;
    }

    @Override
    public void stop() {
        m_stopping = true;
    }

    @Override
    public void rateLimitedLog(Level level, Throwable cause, String format, Object... args) {
        m_log.rateLimitedLog(LOG_SUPPRESSION_INTERVAL_SECONDS, level, cause, format, args);
    }

    @Override
    public void info(Throwable t, String msgFormat, Object... args) {
        m_log.info(String.format(msgFormat, args), t);
    }

    @Override
    public void warn(Throwable t, String msgFormat, Object... args) {
        m_log.warn(String.format(msgFormat, args), t);
    }

    @Override
    public void error(Throwable t, String msgFormat, Object... args) {
        m_log.error(String.format(msgFormat, args), t);
    }

    @Override
    public void debug(Throwable t, String msgFormat, Object... args) {
        m_log.debug(String.format(msgFormat, args), t);
    }

    @Override
    public boolean isDebugEnabled() {
        return m_log.isDebugEnabled();
    }

    @Override
    public boolean hasTransaction() {
        return true;
    }

    private ExecutorService getConsumerExecutor(CSVDataLoader loader, final ImporterSupport wrapper) throws Exception {

        // NEEDSWORK: We don't need the configuration to tell us how many partitions we have.  Is this OK,
        // can we remove that value?  This will impact the doc.

        // NEEDSWORK: Do we still need the Zookeeper config options?  No place for them, as it stands.

        Map<URI, KafkaStreamImporterConfig> configs = createKafkaImporterConfigFromProperties(m_config);
        ExecutorService executor = Executors.newFixedThreadPool(configs.size());
        System.out.println("Created " + configs.size() + " configurations for partitions:");

        for (URI uri : configs.keySet()) {
            System.out.println(" " + uri);
            KafkaStreamImporterConfig cfg = configs.get(uri);
            LoaderTopicPartitionImporter importer = new LoaderTopicPartitionImporter(cfg , wrapper);
            executor.submit(importer);
        }

        return executor;
    }

    private Map<URI, KafkaStreamImporterConfig> createKafkaImporterConfigFromProperties(KafkaExternalLoaderCLIArguments properties) throws Exception {

        List<HostAndPort> brokers = getBrokersFromZookeeper(properties.zookeeper);

        // Concatenate the host:port values of each broker into one string that can be used to compute the key:
        String brokersString = StringUtils.join(brokers.stream().map(s -> s.getHost() + ":" + s.getPort()).collect(Collectors.toList()), ",");
        String brokerKey = KafkaStreamImporterConfig.getBrokerKey(brokersString);

        String groupId;
        if (properties.groupid == null || properties.groupid.trim().length() == 0) {
            groupId = "voltdb-" + (m_config.useSuppliedProcedure ? m_config.procedure : m_config.table);
        }
        else {
            groupId = properties.groupid.trim();
        }

        // NEEDSWORK: Should these be customizable?
        int fetchSize = 65536;
        int soTimeout = 30000;

        String procedure = properties.procedure;
        String topic = properties.topic;
        String commitPolicy = "NONE";

        Properties props = new Properties();
        props.put("procedure", procedure);
        FormatterBuilder fb = createFormatterBuilder(properties);

        return KafkaStreamImporterConfig.getConfigsForPartitions(brokerKey, brokers, topic, groupId, procedure, soTimeout, fetchSize, commitPolicy, fb);
    }

    private FormatterBuilder createFormatterBuilder(KafkaExternalLoaderCLIArguments properties) throws Exception {

        FormatterBuilder fb;
        Properties formatterProperties = m_config.m_formatterProperties;
        AbstractFormatterFactory factory;

        if (formatterProperties.size() > 0) {

            String formatterClass = m_config.m_formatterProperties.getProperty("formatter");
            String format = m_config.m_formatterProperties.getProperty("format", "csv");
            Class<?> classz = Class.forName(formatterClass);
            Class<?>[] ctorParmTypes = new Class[]{ String.class, Properties.class };
            Constructor<?> ctor = classz.getDeclaredConstructor(ctorParmTypes);
            Object[] ctorParms = new Object[]{ format, m_config.m_formatterProperties };

            factory = new AbstractFormatterFactory() {
                @Override
                public Formatter create(String formatName, Properties props) {
                    try {
                        return (Formatter) ctor.newInstance(ctorParms);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }

                }
            };
            fb = new FormatterBuilder(format, formatterProperties);
        }
        else {
            factory = new VoltCSVFormatterFactory();
            Properties props = new Properties();
            factory.create("csv", props);
            fb = new FormatterBuilder("csv", props);
        }

        fb.setFormatterFactory(factory);
        return fb;

    }

    private static Client getVoltClient(ClientConfig config, String[] servers, int port) throws Exception {
        final Client client = ClientFactory.createClient(config);
        for (String server : servers) {
            client.createConnection(server.trim(), port);
        }
        return client;
    }

    private static List<HostAndPort> getBrokersFromZookeeper(String zookeeperHost) throws Exception {
        ZooKeeper zk = new ZooKeeper(zookeeperHost, 10000, null, new HashSet<Long>());
        ArrayList<HostAndPort> brokers = new ArrayList<HostAndPort>();
        List<String> ids = zk.getChildren("/brokers/ids", false);
        for (String id : ids) {
            String brokerInfo = new String(zk.getData("/brokers/ids/" + id, false, null));
            Broker broker = Broker.createBroker(Integer.valueOf(id), brokerInfo);
            if (broker != null) {
                System.out.println("Adding broker: " + broker.connectionString());
                brokers.add(new HostAndPort(broker.host(), broker.port()));
            }
        }
        return brokers;
    }

    class LoaderTopicPartitionImporter extends BaseKafkaTopicPartitionImporter implements Runnable {

        public LoaderTopicPartitionImporter(KafkaStreamImporterConfig config, ImporterSupport wrapper) {
            super(config, wrapper);
        }

        @Override
        public boolean executeVolt(Object[] params, TopicPartitionInvocationCallback cb) {
           try {
               m_loader.insertRow(new RowWithMetaData(StringUtils.join(params, ","), cb.getOffset()), params);
               cb.clientCallback(new ClientResponse() {

                   @Override
                   public int getClientRoundtrip() {
                       // TODO Auto-generated method stub
                       return 0;
                   }

                   @Override
                   public int getClusterRoundtrip() {
                       // TODO Auto-generated method stub
                       return 0;
                   }

                   @Override
                   public String getStatusString() {
                       return null;
                   }

                   @Override
                   public VoltTable[] getResults() {
                       return null;
                   }

                   @Override
                   public byte getStatus() {
                       return ClientResponse.SUCCESS;
                   }

                   @Override
                   public byte getAppStatus() {
                       return 0;
                   }

                   @Override
                   public String getAppStatusString() {
                       return null;
                   }

                   @Override
                   public long getClientRoundtripNanos() {
                       // TODO Auto-generated method stub
                       return 0;
                   }
               });

           }
           catch (Exception e) {
               e.printStackTrace();
               return false;
           }
           return true;
        }

        @Override
        public void run() {
            accept();
        }

    }

    private static class KafkaExternalLoaderCLIArguments extends CLIConfig {

        @Option(shortOpt = "p", desc = "Procedure name to insert the data into the database")
        String procedure = "";

        // This is set to true when -p option is used.
        boolean useSuppliedProcedure = false;

        @Option(shortOpt = "t", desc = "Kafka Topic to subscribe to")
        String topic = "";

        @Option(shortOpt = "g", desc = "Kafka group-id")
        String groupid = "";

        @Option(shortOpt = "m", desc = "Maximum errors allowed before terminating import")
        int maxerrors = 100;

        @Option(shortOpt = "s", desc = "List of VoltDB servers to connect to (default: localhost)")
        String servers = "localhost";

        @Option(desc = "Port to use when connecting to VoltDB servers (default: 21212)")
        int port = Client.VOLTDB_SERVER_PORT;

        @Option(desc = "Username for connecting to VoltDB servers")
        String user = "";

        @Option(desc = "Password for connecting to VoltDB servers")
        String password = "";

        @Option(shortOpt = "z", desc = "Kafka Zookeeper to connect to. (format: zkserver:port)")
        String zookeeper = ""; //No default here as default will clash with local voltdb cluster

        @Option(shortOpt = "f", desc = "Periodic Flush Interval in seconds. (default: 10)")
        int flush = 10;

        @Option(desc = "Formatter configuration file. (Optional) .")
        String formatter = "";

        @Option(desc = "Batch Size for processing.")
        public int batch = 200;

        @AdditionalArgs(desc = "Insert the data into this table.")
        public String table = "";

        @Option(desc = "Use upsert instead of insert", hasArg = false)
        boolean update = false;

        @Option(desc = "Enable SSL, optionally provide configuration file.")
        String ssl = "";

        //Read properties from formatter option and do basic validation.
        Properties m_formatterProperties = new Properties();

        /**
         * Validate command line options.
         */
        @Override
        public void validate() {

            if (batch < 0) {
                exitWithMessageAndUsage("batch size number must be >= 0");
            }
            if (flush <= 0) {
                exitWithMessageAndUsage("Periodic Flush Interval must be > 0");
            }
            if (topic.trim().isEmpty()) {
                exitWithMessageAndUsage("Topic must be specified.");
            }
            if (zookeeper.trim().isEmpty()) {
                exitWithMessageAndUsage("Kafka Zookeeper must be specified.");
            }
            if (port < 0) {
                exitWithMessageAndUsage("port number must be >= 0");
            }
            if (procedure.trim().isEmpty() && table.trim().isEmpty()) {
                exitWithMessageAndUsage("procedure name or a table name required");
            }
            if (!procedure.trim().isEmpty() && !table.trim().isEmpty()) {
                exitWithMessageAndUsage("Only a procedure name or a table name required, pass only one please");
            }
            if (!procedure.trim().isEmpty()) {
                useSuppliedProcedure = true;
            }
            if ((useSuppliedProcedure) && (update)){
                update = false;
                exitWithMessageAndUsage("update is not applicable when stored procedure specified");
            }

            //Try and load classes we need and not packaged.
            try {
                KafkaExternalLoader.class.getClassLoader().loadClass("org.I0Itec.zkclient.IZkStateListener");
                KafkaExternalLoader.class.getClassLoader().loadClass("org.apache.zookeeper.Watcher");
            }
            catch (ClassNotFoundException cnfex) {
                System.out.println("Cannot find the Zookeeper libraries, zkclient-0.3.jar and zookeeper-3.3.4.jar.");
                System.out.println("Use the ZKLIB environment variable to specify the path to the Zookeeper jars files.");
                System.exit(1);
            }
        }
    }

    public static void main(String[] args) {

        final KafkaExternalLoaderCLIArguments cfg = new KafkaExternalLoaderCLIArguments();
        cfg.parse(KafkaExternalLoader.class.getName(), args);

        try {
            if (!cfg.formatter.trim().isEmpty()) {
                InputStream pfile = new FileInputStream(cfg.formatter);
                cfg.m_formatterProperties.load(pfile);
                String formatter = cfg.m_formatterProperties.getProperty("formatter");
                if (formatter == null || formatter.trim().isEmpty()) {
                    m_log.error("formatter class must be specified in formatter file as formatter=<class>: " + cfg.formatter);
                    System.exit(-1);
                }
            }
            KafkaExternalLoader kloader = new KafkaExternalLoader(cfg);
            kloader.processKafkaMessages();
        }
        catch (Exception e) {
            m_log.error("Failure in kafkaloader", e);
            System.exit(-1);
        }

        System.exit(0);
    }


}
