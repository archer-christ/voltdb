//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, vJAXB 2.1.10 in JDK 6 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2010.09.13 at 04:03:56 PM EDT 
//


package org.voltdb.compiler.deploymentfile;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for deploymentType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="deploymentType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="cluster" type="{}clusterType"/>
 *         &lt;element name="users" type="{}usersType" minOccurs="0"/>
 *         &lt;element name="httpd" type="{}httpdType" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "deploymentType", propOrder = {
    "cluster",
    "users",
    "httpd"
})
public class DeploymentType {

    @XmlElement(required = true)
    protected ClusterType cluster;
    protected UsersType users;
    protected HttpdType httpd;

    /**
     * Gets the value of the cluster property.
     * 
     * @return
     *     possible object is
     *     {@link ClusterType }
     *     
     */
    public ClusterType getCluster() {
        return cluster;
    }

    /**
     * Sets the value of the cluster property.
     * 
     * @param value
     *     allowed object is
     *     {@link ClusterType }
     *     
     */
    public void setCluster(ClusterType value) {
        this.cluster = value;
    }

    /**
     * Gets the value of the users property.
     * 
     * @return
     *     possible object is
     *     {@link UsersType }
     *     
     */
    public UsersType getUsers() {
        return users;
    }

    /**
     * Sets the value of the users property.
     * 
     * @param value
     *     allowed object is
     *     {@link UsersType }
     *     
     */
    public void setUsers(UsersType value) {
        this.users = value;
    }

    /**
     * Gets the value of the httpd property.
     * 
     * @return
     *     possible object is
     *     {@link HttpdType }
     *     
     */
    public HttpdType getHttpd() {
        return httpd;
    }

    /**
     * Sets the value of the httpd property.
     * 
     * @param value
     *     allowed object is
     *     {@link HttpdType }
     *     
     */
    public void setHttpd(HttpdType value) {
        this.httpd = value;
    }

}
