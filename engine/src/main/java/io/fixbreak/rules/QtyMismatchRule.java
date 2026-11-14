package io.fixbreak.rules;

import io.fixbreak.order.OrderState;
import io.fixbreak.order.OrderUpdate;
import io.fixbreak.session.DirectionState;
import io.fixbreak.session.SeqGap;

import java.util.LinkedHashSet;
import java.util.Set;

/** Quantities on an ExecutionReport that do not add up. */
public final class QtyMismatchRule implements Rule {
    /**
     * Orders whose fills did not add up while their session had an open sequence gap: the
     * missing report may still arrive as a resend, so they are re-checked at end of log.
     */
    private final Set<OrderState> deferred = new LinkedHashSet<>();

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
        if (u.lateResend) return; // its CumQty is older than what was already applied; checked at end if needed
        String problem = null;
        // Only meaningful when the log shows the order from its start; otherwise earlier fills are simply out of view.
        if (o.seenNew() && Math.abs(o.sumLastQty - u.cumQty) > Describe.EPS && hasOpenGap(ctx.direction)) {
            deferred.add(o);
            return;
        }
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

    @Override
    public void onEnd(ScanContext ctx, FindingSink out) {
        for (OrderState o : deferred) {
            if (Math.abs(o.sumLastQty - o.cumQty) <= Describe.EPS || !o.markOnce(OrderState.MARK_QTY_MISMATCH)) continue;
            out.report(this, Severity.HIGH, o.session, o, o.newTime, o.lastTime,
                    "Order " + Describe.order(o, ctx.dict()) + " quantity mismatch: CumQty(14)=" + Describe.qty(o.cumQty)
                            + " but the fills reported (LastQty(32)) add up to " + Describe.qty(o.sumLastQty)
                            + "; a sequence gap was open when the totals diverged and the missing report never arrived",
                    o.refFromEnd(1), o.refFromEnd(2), o.newRef);
        }
    }

    /** True if the direction has a recent gap that resends have not yet covered. */
    private static boolean hasOpenGap(DirectionState d) {
        if (d == null) return false;
        for (int i = d.gaps.size() - 1, n = 0; i >= 0 && n < 64; i--, n++) {
            SeqGap g = d.gaps.get(i);
            if (!g.tooLow && !g.hardReset && !g.recovered()) return true;
        }
        return false;
    }
}
