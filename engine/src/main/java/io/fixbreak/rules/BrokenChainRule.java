package io.fixbreak.rules;

import io.fixbreak.order.OrderUpdate;

/** OrigClOrdID(41) that points at a ClOrdID the log never introduced. */
public final class BrokenChainRule implements Rule {
    @Override
    public String id() {
        return "BROKEN_CHAIN";
    }

    @Override
    public String description() {
        return "An OrigClOrdID(41) refers to a ClOrdID that never appears in the log, so the amend/cancel chain cannot be followed.";
    }

    @Override
    public void onMessage(MessageContext ctx, FindingSink out) {
        OrderUpdate u = ctx.update;
        if (u == null || u.unknownOrigClOrdId == null) return;
        String what = switch (u.msgType) {
            case 'F' -> "Cancel request";
            case 'G' -> "Cancel/replace request";
            default -> "ExecutionReport for";
        };
        out.report(this, Severity.MEDIUM, ctx.session, u.order, ctx.msg.time(), ctx.msg.time(),
                what + " " + u.clOrdId + " from " + ctx.direction.sender + " refers to OrigClOrdID(41)=" + u.unknownOrigClOrdId
                        + ", which this log never shows as a ClOrdID(11)",
                ctx.msg.ref());
    }
}
