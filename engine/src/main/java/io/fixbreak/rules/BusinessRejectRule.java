package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.Tags;

/** BusinessMessageReject (35=j): the message was valid FIX but the application could not process it. */
public final class BusinessRejectRule implements Rule {
    @Override
    public String id() {
        return "BUSINESS_REJECT";
    }

    @Override
    public String description() {
        return "A message was refused with BusinessMessageReject(35=j): valid FIX, but the application would not process it.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        if (!ctx.msgType.equals("j")) return;
        OrderUpdate u = ctx.update;
        StringBuilder sb = new StringBuilder("BusinessMessageReject(j) from ").append(ctx.direction.sender);
        String refType = ctx.msg.get(Tags.REF_MSG_TYPE);
        String refSeq = ctx.msg.get(Tags.REF_SEQ_NUM);
        String refId = ctx.msg.get(Tags.BUSINESS_REJECT_REF_ID);
        sb.append(" for ").append(refType == null ? "message" : ctx.dict.msgTypeName(refType));
        if (refSeq != null) sb.append(" MsgSeqNum ").append(refSeq);
        boolean linked = u != null && u.order != null;
        if (linked) sb.append(" (order ").append(Describe.order(u.order, ctx.dict)).append(')');
        else if (refId != null) sb.append(" (BusinessRejectRefID(379)=").append(refId).append(')');
        String reason = ctx.describe(Tags.BUSINESS_REJECT_REASON);
        sb.append(": BusinessRejectReason(380)=").append(reason == null ? "not given" : reason);
        sb.append(ctx.textSuffix());
        out.report(this, Severity.HIGH, ctx.session, linked ? u.order : null,
                linked ? u.order.newTime : ctx.msg.time(), ctx.msg.time(), sb.toString(),
                u == null ? 0 : u.referencedRef, linked ? u.order.newRef : 0, ctx.msg.ref());
    }
}
