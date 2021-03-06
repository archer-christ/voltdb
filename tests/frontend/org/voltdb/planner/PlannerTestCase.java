/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.planner;

import java.net.URL;
import java.util.EmptyStackException;
import java.util.List;
import java.util.Stack;

import org.apache.commons.lang3.StringUtils;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.Database;
import org.voltdb.compiler.DeterminismMode;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.NestLoopPlanNode;
import org.voltdb.plannodes.OrderByPlanNode;
import org.voltdb.plannodes.SeqScanPlanNode;
import org.voltdb.types.ExpressionType;
import org.voltdb.types.JoinType;
import org.voltdb.types.PlanNodeType;
import org.voltdb.types.SortDirectionType;

import junit.framework.TestCase;

public class PlannerTestCase extends TestCase {

    private PlannerTestAideDeCamp m_aide;
    private boolean m_byDefaultInferPartitioning = true;
    private boolean m_byDefaultPlanForSinglePartition;
    final private int m_defaultParamCount = 0;
    private String m_noJoinOrder = null;

    /**
     * @param sql
     * @return
     */
    private int countQuestionMarks(String sql) {
        int paramCount = 0;
        int skip = 0;
        while (true) {
            // Yes, we ARE assuming that test queries don't contain quoted question marks.
            skip = sql.indexOf('?', skip);
            if (skip == -1) {
                break;
            }
            skip++;
            paramCount++;
        }
        return paramCount;
    }

    protected void failToCompile(String sql, String... patterns) {
        int paramCount = countQuestionMarks(sql);
        try {
            List<AbstractPlanNode> unexpected = m_aide.compile(sql, paramCount,
                    m_byDefaultInferPartitioning, m_byDefaultPlanForSinglePartition, null);
            printExplainPlan(unexpected);
            fail("Expected planner failure, but found success.");
        }
        catch (Exception ex) {
            String result = ex.toString();
            for (String pattern : patterns) {
                if ( ! result.contains(pattern)) {
                    fail("Did not find pattern '" + pattern + "' in error string '" + result + "'");
                }
            }
        }
    }

    protected CompiledPlan compileAdHocPlan(String sql) {
        return compileAdHocPlan(sql, DeterminismMode.SAFER);
    }

    protected CompiledPlan compileAdHocPlan(String sql, DeterminismMode detMode) {
        CompiledPlan cp = null;
        try {
            cp = m_aide.compileAdHocPlan(sql, detMode);
            assertTrue(cp != null);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            fail(ex.getMessage());
        }
        return cp;
    }

    /**
     * Fetch compiled planned based on provided partitioning information.
     * @param sql: SQL statement
     * @param inferPartitioning: Flag to indicate whether to use infer or forced partitioning
     *                           when generating plan. True to use infer partitioning info,
     *                           false for forced partitioning
     * @param forcedSP: Flag to indicate whether to generate plan for forced SP or MP.
     *                  If inferPartitioing flag is set to true, this flag is ignored
     * @param detMode: Specifies determinism mode - Faster or Safer
     * @return: Compiled plan based on specified input parameters
     */

    protected CompiledPlan compileAdHocPlan(String sql,
                                            boolean inferPartitioning,
                                            boolean forcedSP,
                                            DeterminismMode detMode) {
        CompiledPlan cp = null;
        try {
            cp = m_aide.compileAdHocPlan(sql, inferPartitioning, forcedSP, detMode);
            assertTrue(cp != null);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            fail();
        }
        return cp;
    }

    protected CompiledPlan compileAdHocPlan(String sql,
                                            boolean inferPartitioning,
                                            boolean forcedSP) {
        return compileAdHocPlan(sql, inferPartitioning, forcedSP, DeterminismMode.SAFER);
    }

    protected List<AbstractPlanNode> compileInvalidToFragments(String sql) {
        boolean planForSinglePartitionFalse = false;
        return compileWithJoinOrderToFragments(sql, m_defaultParamCount,
                planForSinglePartitionFalse, m_noJoinOrder);
    }

    /** A helper here where the junit test can assert success */
    protected List<AbstractPlanNode> compileToFragments(String sql) {
        boolean planForSinglePartitionFalse = false;
        return compileWithJoinOrderToFragments(sql, planForSinglePartitionFalse, m_noJoinOrder);
    }

    protected List<AbstractPlanNode> compileToFragmentsForSinglePartition(String sql) {
        boolean planForSinglePartitionFalse = false;
        return compileWithJoinOrderToFragments(sql, planForSinglePartitionFalse, m_noJoinOrder);
    }


    /** A helper here where the junit test can assert success */
    protected List<AbstractPlanNode> compileWithJoinOrderToFragments(String sql, String joinOrder) {
        boolean planForSinglePartitionFalse = false;
        return compileWithJoinOrderToFragments(sql, planForSinglePartitionFalse, joinOrder);
    }

    /** A helper here where the junit test can assert success */
    private List<AbstractPlanNode> compileWithJoinOrderToFragments(String sql,
                                                                   boolean planForSinglePartition,
                                                                   String joinOrder) {
        try {
            // Yes, we ARE assuming that test queries don't contain quoted question marks.
            int paramCount = StringUtils.countMatches(sql, "?");
            return compileWithJoinOrderToFragments(sql, paramCount, planForSinglePartition, joinOrder);
        }
        catch (PlanningErrorException pe) {
            fail("Query: '" + sql + "' threw " + pe);
            return null; // dead code.
        }
    }

    /** A helper here where the junit test can assert success */
    private List<AbstractPlanNode> compileWithJoinOrderToFragments(String sql, int paramCount,
                                                                   boolean planForSinglePartition,
                                                                   String joinOrder) {
        //* enable to debug */ System.out.println("DEBUG: compileWithJoinOrderToFragments(\"" + sql + "\", " + planForSinglePartition + ", \"" + joinOrder + "\")");
        List<AbstractPlanNode> pn = m_aide.compile(sql, paramCount, m_byDefaultInferPartitioning, m_byDefaultPlanForSinglePartition, joinOrder);
        assertTrue(pn != null);
        assertFalse(pn.isEmpty());
        assertTrue(pn.get(0) != null);
        if (planForSinglePartition) {
            assertTrue(pn.size() == 1);
        }
        return pn;
    }

    protected AbstractPlanNode compileSPWithJoinOrder(String sql, String joinOrder) {
        try {
            return compileWithCountedParamsAndJoinOrder(sql, joinOrder);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            fail();
            return null;
        }
    }

    protected void compileWithInvalidJoinOrder(String sql, String joinOrder) throws Exception {
        compileWithJoinOrderToFragments(sql, m_defaultParamCount, m_byDefaultPlanForSinglePartition, joinOrder);
    }


    private AbstractPlanNode compileWithCountedParamsAndJoinOrder(String sql,
                                                                  String joinOrder) throws Exception {
        // Yes, we ARE assuming that test queries don't contain quoted question marks.
        int paramCount = StringUtils.countMatches(sql, "?");
        return compileSPWithJoinOrder(sql, paramCount, joinOrder);
    }

    /**
     * Assert that the plan for a statement produces a plan that meets some
     * basic expectations.
     * @param sql a statement to plan
     *            as if for a single-partition stored procedure
     * @param nOutputColumns the expected number of plan result columns,
     *                       because of the planner's history of such errors
     * @param nodeTypes the expected node types of the resulting plan tree
     *                  listed in top-down order with wildcard support.
     *                  See assertTopDownTree.
     * @return the plan for more detailed testing.
     */
    protected AbstractPlanNode compileToTopDownTree(String sql,
            int nOutputColumns, PlanNodeType... nodeTypes) {
        // Yes, we ARE assuming that test queries don't
        // contain quoted question marks.
        int paramCount = StringUtils.countMatches(sql, "?");
        AbstractPlanNode result = compileSPWithJoinOrder(sql, paramCount, null);
        assertEquals(nOutputColumns, result.getOutputSchema().size());
        assertTopDownTree(result, nodeTypes);
        return result;
    }

    /** A helper here where the junit test can assert success */
    protected AbstractPlanNode compile(String sql) {
        // Yes, we ARE assuming that test queries don't contain quoted question marks.
        int paramCount = StringUtils.countMatches(sql, "?");
        return compileSPWithJoinOrder(sql, paramCount, null);
    }

    /** A helper here where the junit test can assert success */
    protected AbstractPlanNode compileForSinglePartition(String sql) {
        // Yes, we ARE assuming that test queries don't contain quoted question marks.
        int paramCount = StringUtils.countMatches(sql, "?");
        boolean m_infer = m_byDefaultInferPartitioning;
        boolean m_forceSP = m_byDefaultInferPartitioning;
        m_byDefaultInferPartitioning = false;
        m_byDefaultPlanForSinglePartition = true;

        AbstractPlanNode pn = compileSPWithJoinOrder(sql, paramCount, null);
        m_byDefaultInferPartitioning = m_infer;
        m_byDefaultPlanForSinglePartition = m_forceSP;
        return pn;
    }

    /** A helper here where the junit test can assert success */
    protected AbstractPlanNode compileSPWithJoinOrder(String sql,
                                                      int paramCount,
                                                      String joinOrder) {
        List<AbstractPlanNode> pns = null;
        try {
            pns = compileWithJoinOrderToFragments(sql, paramCount, m_byDefaultPlanForSinglePartition, joinOrder);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            fail(ex.getMessage());
        }
        assertTrue(pns.get(0) != null);
        return pns.get(0);
    }

    /**
     *  Find all the aggregate nodes in a fragment, whether they are hash, serial or partial.
     * @param fragment     Fragment to search for aggregate plan nodes
     * @return a list of all the nodes we found
     */
    protected static List<AbstractPlanNode> findAllAggPlanNodes(AbstractPlanNode fragment) {
        List<AbstractPlanNode> aggNodes = fragment.findAllNodesOfType(PlanNodeType.AGGREGATE);
        List<AbstractPlanNode> hashAggNodes = fragment.findAllNodesOfType(PlanNodeType.HASHAGGREGATE);
        List<AbstractPlanNode> partialAggNodes = fragment.findAllNodesOfType(PlanNodeType.PARTIALAGGREGATE);

        aggNodes.addAll(hashAggNodes);
        aggNodes.addAll(partialAggNodes);
        return aggNodes;
    }


    protected void setupSchema(URL ddlURL, String basename,
                               boolean planForSinglePartition) throws Exception {
        m_aide = new PlannerTestAideDeCamp(ddlURL, basename);
        m_byDefaultPlanForSinglePartition = planForSinglePartition;
    }

    protected void setupSchema(boolean inferPartitioning, URL ddlURL,
                               String basename) throws Exception {
        m_byDefaultInferPartitioning = inferPartitioning;
        m_aide = new PlannerTestAideDeCamp(ddlURL, basename);
    }

    public String getCatalogString() {
        return m_aide.getCatalogString();
    }

    public Catalog getCatalog() {
        return m_aide.getCatalog();
    }

    Database getDatabase() {
        return m_aide.getDatabase();
    }

    protected void printExplainPlan(List<AbstractPlanNode> planNodes) {
        for (AbstractPlanNode apn: planNodes) {
            System.out.println(apn.toExplainPlanString());
        }
    }

    protected String buildExplainPlan(List<AbstractPlanNode> planNodes) {
        String explain = "";
        for (AbstractPlanNode apn: planNodes) {
            explain += apn.toExplainPlanString() + '\n';
        }
        return explain;
    }

    protected void checkQueriesPlansAreTheSame(String sql1, String sql2) {
        String explainStr1, explainStr2;
        List<AbstractPlanNode> pns = compileToFragments(sql1);
        explainStr1 = buildExplainPlan(pns);
        pns = compileToFragments(sql2);
        explainStr2 = buildExplainPlan(pns);

        assertEquals(explainStr1, explainStr2);
    }

    /**
     * Call this function to verify that an order by plan node has the
     * sort expressions and directions we expect.
     *
     * @param orderByPlanNode The plan node to test.
     * @param columnDescrs Pairs of expressions and sort directions. There
     *                     must be an even number of these, the even
     *                     numbered ones must be expressions and the odd
     *                     numbered ones must be sort directions.  This is
     *                     numbering starting at 0.  So, they must be in
     *                     the order expr, direction, expr, direction, and
     *                     so forth.
     */
    protected void verifyOrderByPlanNode(OrderByPlanNode  orderByPlanNode,
                                         Object       ... columnDescrs) {
        // We should have an even number of columns
        assertEquals(0, columnDescrs.length % 2);
        List<AbstractExpression> exprs = orderByPlanNode.getSortExpressions();
        List<SortDirectionType>  dirs  = orderByPlanNode.getSortDirections();
        assertEquals(exprs.size(), dirs.size());
        assertEquals(columnDescrs.length/2, exprs.size());
        for (int idx = 0; idx < exprs.size(); ++idx) {
            // Assert that an expected one-part name matches a tve by column name
            // and an expected two-part name matches a tve by table and column name.
            AbstractExpression expr = exprs.get(idx);
            assertTrue(expr instanceof TupleValueExpression);
            TupleValueExpression tve = (TupleValueExpression)expr;
            String expectedNames[] = ((String)columnDescrs[2*idx]).split("\\.");
            String columnName = null;
            int nParts = expectedNames.length;
            if (nParts > 1) {
                assertEquals(2, nParts);
                String tableName = expectedNames[0].toUpperCase();
                assertEquals(tableName, tve.getTableName().toUpperCase());
            }
            // In either case, the column name must match the LAST part.
            columnName = expectedNames[nParts-1].toUpperCase();
            assertEquals(columnName, tve.getColumnName().toUpperCase());

            SortDirectionType dir = dirs.get(idx);
            assertEquals(columnDescrs[2*idx+1], dir);
        }
    }

    /**
     * Assert that a plan's left-most branch is made up of plan nodes of
     * specified classes.
     * @param expectedClasses a list of expected AbstractPlanNode classes
     * @param actualPlan the top of a plan node tree expected to have instances
     *                   of the expected classes along its left-most branch
     *                   listed from top to bottom.
     */
    static protected void assertClassesMatchNodeChain(
            List<Class<? extends AbstractPlanNode>> expectedClasses,
            AbstractPlanNode actualPlan) {
        AbstractPlanNode pn = actualPlan;
        for (Class<? extends AbstractPlanNode> c : expectedClasses) {
            assertFalse("The actual plan is shallower than expected",
                    pn == null);
            assertTrue("Expected plan to contain an instance of " + c.getSimpleName() +", "
                    + "instead found " + pn.getClass().getSimpleName(),
                    c.isInstance(pn));
            if (pn.getChildCount() > 0) {
                pn = pn.getChild(0);
            }
            else {
                pn = null;
            }
        }

        assertTrue("Actual plan longer than expected", pn == null);
    }

    /**
     * Find a specific node in a plan tree following the left-most path,
     * (child[0]), and asserting the expected class of each plan node along the
     * way, inclusive of the start and end.
     * @param expectedClasses a list of expected AbstractPlanNode classes
     * @param actualPlan the top of a plan node tree expected to have instances
     *                   of the expected classes along its left-most branch
     *                   listed in top-down order.
     * @return the child node matching the last expected class in the list.
     *                   It need not be a leaf node.
     */
    protected static AbstractPlanNode followAssertedLeftChain(
            AbstractPlanNode start,
            PlanNodeType startType, PlanNodeType... nodeTypes) {
        AbstractPlanNode result = start;
        assertEquals(startType, result.getPlanNodeType());
        for (PlanNodeType type : nodeTypes) {
            assertTrue(result.getChildCount() > 0);
            result = result.getChild(0);
            assertEquals(type, result.getPlanNodeType());
        }
        return result;
    }

    /**
     * Assert that a plan's left-most branch is made up of plan nodes of
     * expected classes.
     * @param expectedClasses a list of expected AbstractPlanNode classes
     * @param actualPlan the top of a plan node tree expected to have instances
     *                   of the expected classes along its left-most branch
     *                   listed from top to bottom.
     */
    protected static void assertLeftChain(
            AbstractPlanNode start, PlanNodeType... nodeTypes) {
        AbstractPlanNode pn = start;
        for (PlanNodeType type : nodeTypes) {
            assertFalse("Child node(s) are missing from the actual plan chain.",
                    pn == null);
            if ( ! type.equals(pn.getPlanNodeType())) {
                fail("Expecting plan node of type " + type + ", " +
                        "instead found " + pn.getPlanNodeType() + ".");
            }
            pn = (pn.getChildCount() > 0) ? pn.getChild(0) : null;
        }
        assertTrue("Actual plan chain was longer than expected",
                pn == null);
    }

    /**
     * Assert that a two-fragment plan's coordinator fragment does a simple
     * projection.
     **/
    protected static void assertProjectingCoordinator(
            List<AbstractPlanNode> lpn) {
        AbstractPlanNode pn;
        pn = lpn.get(0);
        assertTopDownTree(pn, PlanNodeType.SEND,
                PlanNodeType.PROJECTION,
                PlanNodeType.RECEIVE);
    }

    /**
     * Assert that a two-fragment plan's coordinator fragment does a left join
     * with a specific replicated table on its outer side.
     **/
    protected static void assertReplicatedLeftJoinCoordinator(
            List<AbstractPlanNode> lpn, String replicatedTable) {
        AbstractPlanNode pn;
        AbstractPlanNode node;
        NestLoopPlanNode nlj;
        SeqScanPlanNode seqScan;
        pn = lpn.get(0);
        assertTopDownTree(pn, PlanNodeType.SEND,
                PlanNodeType.PROJECTION,
                PlanNodeType.NESTLOOP,
                PlanNodeType.SEQSCAN,
                PlanNodeType.RECEIVE);
        node = followAssertedLeftChain(pn, PlanNodeType.SEND,
                PlanNodeType.PROJECTION,
                PlanNodeType.NESTLOOP);
        nlj = (NestLoopPlanNode) node;
        assertEquals(JoinType.LEFT, nlj.getJoinType());
        assertEquals(2, nlj.getChildCount());
        seqScan = (SeqScanPlanNode) nlj.getChild(0);
        assertEquals(replicatedTable, seqScan.getTargetTableName().toUpperCase());
    }

    // Print a tree of plan nodes by type.
    protected void printPlanNodes(AbstractPlanNode root, int fragmentNumber, int numberOfFragments) {
        System.out.printf("  Plan for fragment %d of %d\n",
                          fragmentNumber,
                          numberOfFragments);
        String lines[] = root.toExplainPlanString().split("\n");
        System.out.printf("    Explain:\n");
        for (String line : lines) {
            System.out.printf("      %s\n", line);
        }
        System.out.printf("    Nodes:\n");
        for (;root != null;
                root = (root.getChildCount() > 0) ? root.getChild(0) : null) {
            System.out.printf("      Node type %s\n", root.getPlanNodeType());
            for (int idx = 1; idx < root.getChildCount(); idx += 1) {
                System.out.printf("        Child %d: %s\n", idx, root.getChild(idx).getPlanNodeType());
            }
        }
    }

    /**
     * Assert that an expression tree contains the expected types of expressions
     * in the order listed, assuming a top-down left-to-right depth-first
     * traversal through left, right, and args children.
     * A null expression type in the list will match any expression
     * node or tree at the corresponding position.
     **/
    protected static void assertExprTopDownTree(AbstractExpression start,
            ExpressionType... exprTypes) {
        assertNotNull(start);
        Stack<AbstractExpression> stack = new Stack<>();
        stack.push(start);
        for (ExpressionType type : exprTypes) {
            // Process each node before its children or later siblings.
            AbstractExpression parent;
            try {
                parent = stack.pop();
            }
            catch (EmptyStackException ese) {
                fail("No expression was found in the tree to match type " + type);
                return; // This dead code hushes warnings.
            }
            List<AbstractExpression> args = parent.getArgs();
            AbstractExpression rightExpr = parent.getRight();
            AbstractExpression leftExpr = parent.getLeft();
            int argCount = (args == null) ? 0 : args.size();
            int childCount = argCount +
                    (rightExpr == null ? 0 : 1) +
                    (leftExpr == null ? 0 : 1);
            if (type == null) {
                // A null type wildcard matches any child TREE or NODE.
                System.out.println("DEBUG: Suggestion -- expect " +
                        parent.getExpressionType() +
                        " with " + childCount + " direct children.");
                continue;
            }
            assertEquals(type, parent.getExpressionType());
            // Iterate from the last child to the first.
            while (argCount > 0) {
                // Push each child to be processed before its parent's
                // or its own later siblings (already pushed).
                stack.push(parent.getArgs().get(--argCount));
            }
            if (rightExpr != null) {
                stack.push(rightExpr);
            }
            if (leftExpr != null) {
                stack.push(leftExpr);
            }
        }
        assertTrue("Extra expression node(s) (" + stack.size() +
                ") were found in the tree with no expression type to match",
                stack.isEmpty());
    }

    /**
     * Assert that a plan node tree contains the expected types of plan nodes
     * in the order listed, assuming a top-down left-to-right depth-first
     * traversal through the child vector. A null plan node type in the list
     * will match any plan node or subtree at the corresponding position.
     **/
    protected static void assertTopDownTree(AbstractPlanNode start,
            PlanNodeType... nodeTypes) {
        Stack<AbstractPlanNode> stack = new Stack<>();
        stack.push(start);
        for (PlanNodeType type : nodeTypes) {
            // Process each node before its children or later siblings.
            AbstractPlanNode parent;
            try {
                parent = stack.pop();
            }
            catch (EmptyStackException ese) {
                fail("No node was found in the tree to match node type " + type);
                return; // This dead code hushes warnings.
            }
            int childCount = parent.getChildCount();
            if (type == null) {
                // A null type wildcard matches any child TREE or NODE.
                System.out.println("DEBUG: Suggestion -- expect " +
                        parent.getPlanNodeType() +
                        " with " + childCount + " direct children.");
                continue;
            }
            assertEquals(type, parent.getPlanNodeType());
            // Iterate from the last child to the first.
            while (childCount > 0) {
                // Push each child to be processed before its parent's
                // or its own later (already pushed) siblings.
                stack.push(parent.getChild(--childCount));
            }
        }
        assertTrue("Extra plan node(s) (" + stack.size() +
                ") were found in the tree with no node type to match",
                stack.isEmpty());
    }

    /**
     * Validate a plan, ignoring inline nodes.  This is kind of like
     * PlannerTestCase.compileToTopDownTree.  The differences are
     * <ol>
     *   <li>We only look out out-of-line nodes,</li>
     *   <li>We can compile MP plans and SP plans, and</li>
     *   <li>The boundaries between fragments in MP plans
     *       are marked with PlanNodeType.INVALID.</li>
     *   <li>We can describe inline nodes pretty easily.</li>
     * </ol>
     *
     * See TestWindowFunctions.testWindowFunctionWithIndex for examples
     * of the use of this function.
     *
     * @param SQL The statement text.
     * @param numberOfFragments The number of expected fragments.
     * @param types The plan node types of the inline and out-of-line nodes.
     *              If types[idx] is a PlanNodeType, then the node should
     *              have no inline children.  If types[idx] is an array of
     *              PlanNodeType values then the node has the type types[idx][0],
     *              and it should have types[idx][1..] as inline children.
     */
    protected void validatePlan(String SQL,
                                int numberOfFragments,
                                Object ...types) {
        List<AbstractPlanNode> fragments = compileToFragments(SQL);
        assertEquals(String.format("Expected %d fragments, not %d",
                                   numberOfFragments,
                                   fragments.size()),
                     numberOfFragments,
                     fragments.size());
        int idx = 0;
        int fragment = 1;
        // The index of the last PlanNodeType in types.
        int nchildren = types.length;
        System.out.printf("Plan for <%s>\n", SQL);
        for (AbstractPlanNode plan : fragments) {
            printPlanNodes(plan, fragment, numberOfFragments);
            // The boundaries between fragments are
            // marked with PlanNodeType.INVALID.
            if (fragment > 1) {
                assertEquals("Expected a fragment to start here",
                             PlanNodeType.INVALID,
                             types[idx]);
                idx += 1;
            }
            fragment += 1;
            for (;plan != null; idx += 1) {
                if (types.length <= idx) {
                    fail(String.format("Expected %d plan nodes, but found more.", types.length));
                }
                if (types[idx] instanceof PlanNodeType) {
                    assertEquals(types[idx], plan.getPlanNodeType());
                } else if (types[idx] instanceof PlanNodeType[]) {
                    PlanNodeType childTypes[] = (PlanNodeType[])(types[idx]);
                    assertEquals(childTypes[0], plan.getPlanNodeType());
                    for (int tidx = 1; tidx < childTypes.length; tidx += 1) {
                        PlanNodeType childType = childTypes[tidx];
                        assertTrue(String.format("Expected inline node of type %s", childType),
                                   plan.getInlinePlanNode(childType) != null);
                    }
                } else {
                    fail("Expected a PlanNodeType or an array of PlanNodeTypes here.");
                }
                plan = (plan.getChildCount() > 0) ? plan.getChild(0) : null;
            }
        }
        assertEquals(nchildren, idx);
    }

}
