/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
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

import java.util.ArrayList;
import java.util.List;

import org.voltdb.planner.AbstractParsedStmt;
import org.voltdb.planner.CompiledPlan;
import org.voltdb.plannodes.AbstractJoinPlanNode;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.AbstractScanPlanNode;
import org.voltdb.plannodes.LimitPlanNode;
import org.voltdb.plannodes.ProjectionPlanNode;

public class PushdownLimits extends MicroOptimization {

    @Override
    public List<CompiledPlan> apply(CompiledPlan plan, AbstractParsedStmt parsedStmt) {
        ArrayList<CompiledPlan> retval = new ArrayList<CompiledPlan>();

        AbstractPlanNode planGraph = plan.rootPlanGraph;
        planGraph = recursivelyApply(planGraph);
        plan.rootPlanGraph = planGraph;

        retval.add(plan);
        return retval;
    }

    AbstractPlanNode recursivelyApply(AbstractPlanNode plan) {
        assert(plan != null);

        // depth first:
        //     find LimitPlanNodes with exactly one child
        //     where that child is an AbstractScanPlanNode
        //     disconnect the LimitPlanNode
        //     and inline the LimitPlanNode in to the AbstractScanPlanNode

        ArrayList<AbstractPlanNode> children = new ArrayList<AbstractPlanNode>();
        for (int i = 0; i < plan.getChildCount(); i++)
            children.add(plan.getChild(i));
        plan.clearChildren();

        for (AbstractPlanNode child : children) {
            // TODO this will break when children feed multiple parents
            child = recursivelyApply(child);
            child.clearParents();
            plan.addAndLinkChild(child);
        }

        if ( ! (plan instanceof LimitPlanNode)) {
            return plan;
        }

        if (plan.getChildCount() != 1) {
            assert(plan.getChildCount() == 1);
            return plan;
        }

        AbstractPlanNode child = plan.getChild(0);

        // push into Scans
        if (child instanceof AbstractScanPlanNode) {
            plan.clearChildren();
            child.clearParents();
            child.addInlinePlanNode(plan);
            return child;
        }

        // push down through Projection
        // Replace the chain plan/limit . child/projection . leaf/whatever
        // with recursivelyApply(child/projection . plan/limit . leaf/whatever)
        // == child/projection . recursivelyApply(plan/limit . leaf/whatever)
        if (child instanceof ProjectionPlanNode) {
            assert (child.getChildCount() == 1);
            AbstractPlanNode leaf = child.getChild(0);
            leaf.clearParents();
            plan.clearChildren();
            plan.addAndLinkChild(leaf);
            child.clearChildren();
            child.clearParents();
            child.addAndLinkChild(plan);
            return recursivelyApply(child);
        }

        // push into JOINs
        if (child instanceof AbstractJoinPlanNode) {
            plan.clearChildren();
            child.clearParents();
            child.addInlinePlanNode(plan);
            // TODO: ENG-5399 for LEFT OUTER join with no post-filter, can also push a modified
            // preliminary pushdown-style limit+offset limit node to the left child.
            // AbstractJoinPlanNode ajpn = (AbstractJoinPlanNode)child;
            // if (ajpn.getWherePredicate() == null && ajpn.getJoinType() == JoinType.LEFT) {
            //     AbstractPlanNode leaf = ajpn.getChild(0);
            //     leaf.clearParents();
            //     LimitPlanNode copy = new LimitPlanNode();
            //     copy.set... (See ParsedSelectStmt as an example).
            //     copy.addAndLinkChild(leaf);
            //     // push down further in the left child if it's a scan or a join
            //     AbstractPlanNode limited = recursivelyApply(copy);
            //     ajpn.replaceChild(leaf, limited);
            // }
            return child;
        }

        return plan;

    }

}
