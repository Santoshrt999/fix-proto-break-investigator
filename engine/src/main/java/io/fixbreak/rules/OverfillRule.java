package io.fixbreak.rules;

import io.fixbreak.order.OrderState;
import io.fixbreak.order.OrderUpdate;

/** CumQty(14) greater than OrderQty(38). */
public final class OverfillRule implements Rule {
    @Override
    public String id() {
        return "OVERFILL";
    }

    @Override
    public String description() {
        return "An ExecutionReport shows CumQty(14) greater than OrderQty(38): the order was filled for more than was asked.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.msgType != '8' || !u.applied) return;
        if (Double.isNaN(u.cumQty) || Double.isNaN(u.orderQty) || u.cumQty <= u.orderQty + Describe.EPS) return;
        if (!u.order.markOnce(OrderState.MARK_OVERFILL)) return;
        out.report(this, Severity.CRITICAL, ctx.session, u.order, u.order.newTime, ctx.msg.time(),
                "Order " + Describe.order(u.order, ctx.dict) + " overfilled: CumQty(14)=" + Describe.qty(u.cumQty)
                        + " exceeds OrderQty(38)=" + Describe.qty(u.orderQty) + " by " + Describe.qty(u.cumQty - u.orderQty),
                ctx.msg.ref(), u.order.refFromEnd(1), u.order.newRef);
    }
}
