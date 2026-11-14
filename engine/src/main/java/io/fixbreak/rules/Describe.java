package io.fixbreak.rules;

import io.fixbreak.dict.FixDictionary;
import io.fixbreak.order.OrderState;
import io.fixbreak.parser.Tags;
import io.fixbreak.util.Json;

/** Shared wording helpers so findings read consistently. */
public final class Describe {
    private Describe() {}

    public static final double EPS = 1e-6;

    public static String qty(double q) {
        return Double.isNaN(q) ? "?" : Json.number(q);
    }

    /** "Buy 1000 ACME @ 10.5" from whatever is known about the order. */
    public static String terms(OrderState o, FixDictionary dict) {
        StringBuilder sb = new StringBuilder();
        String side = o.side == 0 ? null : dict.valueName(Tags.SIDE, String.valueOf(o.side));
        if (side != null) sb.append(side).append(' ');
        if (!Double.isNaN(o.orderQty)) sb.append(qty(o.orderQty)).append(' ');
        if (o.symbol != null) sb.append(o.symbol);
        if (!Double.isNaN(o.price)) sb.append(" @ ").append(qty(o.price));
        else if (o.ordType == '1') sb.append(" at market");
        return sb.toString().trim();
    }

    /** "ORD-1 (Buy 1000 ACME @ 10.5)". */
    public static String order(OrderState o, FixDictionary dict) {
        String t = terms(o, dict);
        return t.isEmpty() ? o.currentClOrdId : o.currentClOrdId + " (" + t + ")";
    }

    public static String status(char ordStatus, FixDictionary dict) {
        if (ordStatus == 0) return "no status";
        String n = dict.valueName(Tags.ORD_STATUS, String.valueOf(ordStatus));
        return n == null ? "OrdStatus " + ordStatus : n;
    }

    public static String seconds(long millis) {
        long s = Math.round(millis / 1000.0);
        if (s < 120) return s + "s";
        if (s < 7200) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + (s % 3600 / 60) + "m";
    }
}
