package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.Tags;

/** OrderCancelReject (35=9): a cancel or cancel/replace request was refused. */
public final class CancelReplaceRejectedRule implements Rule {
    @Override
    public String id() {
        return "CANCEL_REPLACE_REJECTED";
    }

    @Override
    public String description() {
        return "A cancel or cancel/replace request was refused with OrderCancelReject(35=9); the order stays as it was.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.msgType != '9') return;
        char responseTo = ctx.msg.getChar(Tags.CXL_REJ_RESPONSE_TO);
        if (responseTo == 0) responseTo = u.referencedMsgType == 'G' ? '2' : u.referencedMsgType == 'F' ? '1' : 0;
        String what = responseTo == '2' ? "Cancel/replace request" : responseTo == '1' ? "Cancel request" : "Cancel or replace request";
        StringBuilder sb = new StringBuilder(what);
        if (u.clOrdId != null) sb.append(' ').append(u.clOrdId);
        if (u.origClOrdId != null) sb.append(" (for ").append(u.origClOrdId).append(')');
        String reason = ctx.describe(Tags.CXL_REJ_REASON);
        sb.append(" rejected by ").append(ctx.direction.sender)
                .append(": CxlRejReason(102)=").append(reason == null ? "not given" : reason);
        String status = ctx.describe(Tags.ORD_STATUS);
        if (status != null) sb.append(", order status at reject ").append(status);
        sb.append(ctx.textSuffix());
        out.report(this, Severity.MEDIUM, ctx.session, u.order, u.order.newTime, ctx.msg.time(), sb.toString(),
                u.referencedRef, ctx.msg.ref(), u.order.newRef);
    }
}
