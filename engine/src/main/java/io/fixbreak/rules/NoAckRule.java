package io.fixbreak.rules;

import io.fixbreak.order.OrderState;
import io.fixbreak.order.OrderUpdate;
import io.fixbreak.util.Times;

/** NewOrderSingle that got no ExecutionReport within the configured timeout. */
public final class NoAckRule implements Rule {
    @Override
    public String id() {
        return "NO_ACK";
    }

    @Override
    public String description() {
        return "A NewOrderSingle received no ExecutionReport within the ack timeout (default 30s, --ack-timeout).";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.msgType != '8' || !u.firstExec) return;
        OrderState o = u.order;
        long now = ctx.msg.time();
        if (!o.seenNew() || o.newTime == Times.UNKNOWN || now == Times.UNKNOWN) return;
        long waited = now - o.newTime;
        if (waited <= ctx.config.ackTimeoutMillis || !o.markOnce(OrderState.MARK_LATE_ACK)) return;
        out.report(this, Severity.MEDIUM, ctx.session, o, o.newTime, now,
                "Order " + Describe.order(o, ctx.dict) + " got its first ExecutionReport after " + Describe.seconds(waited)
                        + " (ack timeout " + Describe.seconds(ctx.config.ackTimeoutMillis) + ")",
                o.newRef, ctx.msg.ref());
    }

    @Override
    public void onEnd(ScanContext ctx, FindingSink out) {
        for (OrderState o : ctx.orders()) {
            if (!o.seenNew() || o.firstExecTime != Times.UNKNOWN || o.rejectedBeforeAck) continue;
            long end = o.session.lastTime;
            if (o.newTime == Times.UNKNOWN || end == Times.UNKNOWN) continue;
            long waited = end - o.newTime;
            if (waited <= ctx.config().ackTimeoutMillis) continue; // log ended too soon to tell
            out.report(this, Severity.HIGH, o.session, o, o.newTime, o.newTime + ctx.config().ackTimeoutMillis,
                    "Order " + Describe.order(o, ctx.dict()) + " was sent but never got an ExecutionReport; the session log continues for "
                            + Describe.seconds(waited) + " after it",
                    o.newRef);
        }
    }
}
