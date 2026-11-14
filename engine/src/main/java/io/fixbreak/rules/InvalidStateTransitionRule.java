package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;

/** ExecutionReports that an order in that state should never receive. */
public final class InvalidStateTransitionRule implements Rule {
    @Override
    public String id() {
        return "INVALID_STATE_TRANSITION";
    }

    @Override
    public String description() {
        return "An order moved in a way the FIX order state model does not allow, e.g. a fill after Filled or Canceled, or CumQty going down.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.msgType != '8' || !u.applied) return;
        if (u.tradeCancel || u.tradeCorrect || u.execType == 'D' || u.execType == 'I') return; // legitimate restatements
        String problem = null;
        Severity severity = Severity.HIGH;
        if (u.wasTerminal) {
            String was = Describe.status(u.prevStatus, ctx.dict);
            if (u.trade) {
                problem = "received a fill (LastQty(32)=" + Describe.qty(u.lastQty) + ", CumQty(14)=" + Describe.qty(u.cumQty)
                        + ") after it was already " + was;
                severity = Severity.CRITICAL;
            } else if (u.ordStatus != 0 && u.ordStatus != u.prevStatus) {
                problem = "went from " + was + " to " + Describe.status(u.ordStatus, ctx.dict)
                        + "; " + was + " is a terminal state";
            }
        } else if (!Double.isNaN(u.cumQty) && u.cumQty < u.prevCumQty - Describe.EPS) {
            problem = "CumQty(14) went down from " + Describe.qty(u.prevCumQty) + " to " + Describe.qty(u.cumQty)
                    + " without a Trade Cancel or Trade Correct";
        }
        if (problem == null) return;
        out.report(this, severity, ctx.session, u.order, u.order.newTime, ctx.msg.time(),
                "Order " + Describe.order(u.order, ctx.dict) + " " + problem,
                ctx.msg.ref(), u.order.refFromEnd(1), u.order.refFromEnd(2), u.order.newRef);
    }
}
