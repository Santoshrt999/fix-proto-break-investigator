package io.fixbreak.rules;

import io.fixbreak.order.OrderState;
import io.fixbreak.order.OrderUpdate;

/** Quantities on an ExecutionReport that do not add up. */
public final class QtyMismatchRule implements Rule {
    @Override
    public String id() {
        return "QTY_MISMATCH";
    }

    @Override
    public String description() {
        return "Quantities do not add up: sum of LastQty(32) differs from CumQty(14), or LeavesQty(151) differs from OrderQty(38) - CumQty(14).";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.msgType != '8' || !u.applied || Double.isNaN(u.cumQty)) return;
        OrderState o = u.order;
        String problem = null;
        // Only meaningful when the log shows the order from its start; otherwise earlier fills are simply out of view.
        if (o.seenNew() && Math.abs(o.sumLastQty - u.cumQty) > Describe.EPS) {
            problem = "CumQty(14)=" + Describe.qty(u.cumQty) + " but the fills reported so far (LastQty(32)) add up to "
                    + Describe.qty(o.sumLastQty);
        } else if (!Double.isNaN(u.leavesQty) && !Double.isNaN(u.orderQty) && !u.wasTerminal
                && u.ordStatus != 0 && !OrderState.isTerminal(u.ordStatus)) {
            double expected = Math.max(0, u.orderQty - u.cumQty);
            if (Math.abs(u.leavesQty - expected) > Describe.EPS) {
                problem = "LeavesQty(151)=" + Describe.qty(u.leavesQty) + " but OrderQty(38) - CumQty(14) = "
                        + Describe.qty(u.orderQty) + " - " + Describe.qty(u.cumQty) + " = " + Describe.qty(expected);
            }
        }
        if (problem == null || !o.markOnce(OrderState.MARK_QTY_MISMATCH)) return;
        out.report(this, Severity.HIGH, ctx.session, o, o.newTime, ctx.msg.time(),
                "Order " + Describe.order(o, ctx.dict) + " quantity mismatch: " + problem,
                ctx.msg.ref(), o.refFromEnd(1), o.refFromEnd(2), o.newRef);
    }
}
