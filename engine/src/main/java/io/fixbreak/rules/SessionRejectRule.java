package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.Tags;

/** Session-level Reject (35=3): the counterparty's FIX engine refused a message as invalid. */
public final class SessionRejectRule implements Rule {
    @Override
    public String id() {
        return "SESSION_REJECT";
    }

    @Override
    public String description() {
        return "A message was refused at session level with Reject(35=3): malformed or invalid per the FIX dictionary.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        if (!ctx.msgType.equals("3")) return;
        OrderUpdate u = ctx.update;
        StringBuilder sb = new StringBuilder("Session Reject(3) from ").append(ctx.direction.sender);
        String refSeq = ctx.msg.get(Tags.REF_SEQ_NUM);
        String refType = ctx.msg.get(Tags.REF_MSG_TYPE);
        if (refType == null && u != null && u.referencedMsgType != 0) refType = String.valueOf(u.referencedMsgType);
        sb.append(" for ");
        sb.append(refType == null ? "message" : ctx.dict.msgTypeName(refType));
        if (refSeq != null) sb.append(" MsgSeqNum ").append(refSeq);
        if (u != null && u.order != null) sb.append(" (order ").append(Describe.order(u.order, ctx.dict)).append(')');
        String reason = ctx.describe(Tags.SESSION_REJECT_REASON);
        sb.append(": SessionRejectReason(373)=").append(reason == null ? "not given" : reason);
        String refTag = ctx.msg.get(Tags.REF_TAG_ID);
        if (refTag != null) {
            Integer tag = ctx.dict.resolveTag(refTag);
            sb.append(", RefTagID(371)=").append(refTag);
            if (tag != null && ctx.dict.tagName(tag) != null) sb.append(" (").append(ctx.dict.tagName(tag)).append(')');
        }
        sb.append(ctx.textSuffix());
        boolean linked = u != null && u.order != null;
        out.report(this, Severity.HIGH, ctx.session, linked ? u.order : null,
                linked ? u.order.newTime : ctx.msg.time(), ctx.msg.time(), sb.toString(),
                u == null ? 0 : u.referencedRef, ctx.msg.ref());
    }
}
