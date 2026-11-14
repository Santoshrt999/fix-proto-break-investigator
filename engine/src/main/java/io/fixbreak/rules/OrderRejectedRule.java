package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.Tags;

/** ExecutionReport with ExecType or OrdStatus = 8 (Rejected). */
public final class OrderRejectedRule implements Rule {
    @Override
    public String id() {
        return "ORDER_REJECTED";
    }

    @Override
    public String description() {
        return "The counterparty rejected the order with an ExecutionReport (ExecType/OrdStatus=8).";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.msgType != '8' || !u.applied) return;
        if (u.execType != '8' && u.ordStatus != '8') return;
        if (u.prevStatus == '8') return; // a repeat of a reject we already reported
        String reason = ctx.describe(Tags.ORD_REJ_REASON);
        String summary = "Order " + Describe.order(u.order, ctx.dict) + " rejected by " + ctx.direction.sender
                + ": OrdRejReason(103)=" + (reason == null ? "not given" : reason) + ctx.textSuffix();
        out.report(this, Severity.MEDIUM, ctx.session, u.order, u.order.newTime, ctx.msg.time(), summary,
                u.order.newRef, ctx.msg.ref());
    }
}
