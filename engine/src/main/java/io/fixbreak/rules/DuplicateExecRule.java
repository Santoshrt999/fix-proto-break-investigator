package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.Tags;

/** The same ExecID(17) reported twice without PossDupFlag or PossResend. */
public final class DuplicateExecRule implements Rule {
    @Override
    public String id() {
        return "DUPLICATE_EXEC";
    }

    @Override
    public String description() {
        return "An ExecID(17) was repeated on a message that is not marked PossDupFlag(43)=Y or PossResend(97)=Y; risk of double booking.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.duplicateExecRef == 0) return;
        String last = ctx.msg.get(Tags.LAST_QTY);
        out.report(this, Severity.HIGH, ctx.session, u.order, u.order.newTime, ctx.msg.time(),
                "ExecID(17)=" + ctx.msg.get(Tags.EXEC_ID) + " on order " + Describe.order(u.order, ctx.dict)
                        + " was sent again as MsgSeqNum " + ctx.msg.get(Tags.MSG_SEQ_NUM) + " without PossDupFlag(43)"
                        + (last == null ? "" : "; LastQty(32)=" + last + " could be booked twice"),
                u.duplicateExecRef, ctx.msg.ref());
    }
}
