package io.fixbreak.rules;

import io.fixbreak.order.OrderState;
import io.fixbreak.util.Times;

/** Partially filled order that never reached a terminal state before the log ended. */
public final class StuckPartialRule implements Rule {
    @Override
    public String id() {
        return "STUCK_PARTIAL";
    }

    @Override
    public String description() {
        return "An order was partially filled and has no terminal state (Filled, Canceled, Expired, Done for day) by end of log.";
    }

    @Override
    public void onEnd(ScanContext ctx, FindingSink out) {
        for (OrderState o : ctx.orders()) {
            if (o.terminal() || o.status == 0 || !(o.cumQty > Describe.EPS)) continue;
            StringBuilder sb = new StringBuilder("Order ").append(Describe.order(o, ctx.dict()))
                    .append(" is partially filled ").append(Describe.qty(o.cumQty)).append('/').append(Describe.qty(o.orderQty));
            if (!Double.isNaN(o.leavesQty)) sb.append(" (LeavesQty ").append(Describe.qty(o.leavesQty)).append(')');
            sb.append(" with status ").append(Describe.status(o.status, ctx.dict())).append(" at end of log");
            if (o.lastTime != Times.UNKNOWN && o.session.lastTime != Times.UNKNOWN && o.session.lastTime > o.lastTime) {
                sb.append("; no activity for the last ").append(Describe.seconds(o.session.lastTime - o.lastTime));
            }
            if (!o.pending().isEmpty()) sb.append("; ").append(o.pending().size()).append(" cancel/replace request(s) unanswered");
            out.report(this, Severity.MEDIUM, o.session, o, o.newTime, o.lastTime, sb.toString(),
                    o.lastRef(), o.refFromEnd(1), o.refFromEnd(2), o.newRef);
        }
    }
}
