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
package org.voltdb.planner.microoptimizations;

import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.NodeSchema;
import org.voltdb.plannodes.ProjectionPlanNode;
import org.voltdb.types.PlanNodeType;

public class RemoveUnnecessaryProjectNodes extends MicroOptimization {

    /**
     * Sometimes when a plan is a single partition plan we end up
     * with an extra, unnecessary projection node.  We can't actually
     * easily remove this in the planner.  The projection node occurs
     * when:
     * <ol>
     *   <li>We push a RECEIVE/SEND pair onto a subplan.  This subplan
     *       would be a join tree or scan, or maybe a subquery.</li>
     *   <li>We push a projection node on top of the R/S pair to calculate
     *       the select list expressions.</li>
     *   <li>We decide the plan is a single partition plan anyway, and
     *       that the R/S pair is not really needed after all.  So, we
     *       eliminate the pair.</li>
     * </ol>
     * At none of these steps do we have enough information to know if
     * the projection node is needed or if it can be eliminated.  So
     * we need to look for these unnecessary projection nodes after
     * the plans are constructed, which means we need a micro-optimization.
     *
     * Now, we can't apply a microoptimization as we usually do, while selecting
     * the best plan, because the output schemas have not been generated
     * when the microoptimizations are being applied.  So we need a
     * special microoptimization which is applied after the plan is
     * in its final state.
     */
    @Override
    protected AbstractPlanNode recursivelyApply(AbstractPlanNode plan) {
        // When we pass in -1 here we are saying
        // we have not come to this node through
        // any parent.  That is to say, this is the root
        // of the plan.
        return recursivelyApply(plan, -1);
    }

    private AbstractPlanNode recursivelyApply(AbstractPlanNode plan, int parentIndex) {
        // Check to see if this is a projection node which may be
        // eliminated.  We may eliminate a string of them here.
        // I don't think this ever happens, but it could.
        while (plan.getPlanNodeType() == PlanNodeType.PROJECTION) {
            ProjectionPlanNode pNode = (ProjectionPlanNode)plan;
            assert(pNode.getChildCount() == 1);
            AbstractPlanNode child = pNode.getChild(0);
            NodeSchema childSchema = child.getOutputSchema();
            assert(childSchema != null);
            AbstractPlanNode parent = (pNode.getParentCount() > 0) ? pNode.getParent(0) : null;
            // Either we have no parent or else we have come
            // down some non-negative child index.
            assert((parentIndex < 0) || (parent != null));
            if (pNode.isIdentity(childSchema)) {
                child.clearParents();
                if (parent != null) {
                    parent.setAndLinkChild(parentIndex, child);
                }
                plan = child;
            } else {
                break;
            }
        }
        for (int idx = 0; idx < plan.getChildCount(); idx += 1) {
            AbstractPlanNode child = plan.getChild(idx);
            AbstractPlanNode newChild = recursivelyApply(child, idx);
            // We've already fixed up the parent
            // in the child and the child in the parent.  So,
            // there is nothing to do here.
        }
        return plan;
    }

    @Override
    MicroOptimizationRunner.Phases getPhase() {
        return MicroOptimizationRunner.Phases.AFTER_BEST_SELECTION;
    }
}
