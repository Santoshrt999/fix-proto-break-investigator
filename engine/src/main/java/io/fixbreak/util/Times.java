package io.fixbreak.util;

import java.time.Instant;

/** Timestamp helpers. All times are epoch milliseconds, UTC. */
public final class Times {
    /** Marker for "no timestamp available". */
    public static final long UNKNOWN = Long.MIN_VALUE;

    private Times() {}

    /** Parses a FIX UTCTimestamp ({@code yyyyMMdd-HH:mm:ss[.fff...]}) from {@code s[from,to)}. */
    public static long parseFix(String s, int from, int to) {
        if (to - from < 17 || s.charAt(from + 8) != '-') return UNKNOWN;
        int date = digits(s, from, 8);
        if (date < 0) return UNKNOWN;
        long tod = timeOfDay(s, from + 9, to);
        if (tod < 0) return UNKNOWN;
        return daysFromCivil(date / 10000, date / 100 % 100, date % 100) * 86_400_000L + tod;
    }

    /** Parses ISO-8601 ({@code yyyy-MM-dd[T ]HH:mm:ss[.fff]}) from {@code s[from,to)}. */
    public static long parseIso(String s, int from, int to) {
        if (to - from < 19 || s.charAt(from + 4) != '-' || s.charAt(from + 7) != '-') return UNKNOWN;
        char sep = s.charAt(from + 10);
        if (sep != 'T' && sep != ' ') return UNKNOWN;
        int y = digits(s, from, 4), m = digits(s, from + 5, 2), d = digits(s, from + 8, 2);
        if (y < 0 || m < 0 || d < 0) return UNKNOWN;
        long tod = timeOfDay(s, from + 11, to);
        if (tod < 0) return UNKNOWN;
        return daysFromCivil(y, m, d) * 86_400_000L + tod;
    }

    /** Accepts either FIX or ISO format, with an optional trailing {@code Z}. Used for CLI arguments. */
    public static long parseLoose(String s) {
        String t = s.trim();
        if (t.endsWith("Z") || t.endsWith("z")) t = t.substring(0, t.length() - 1);
        long v = parseIso(t, 0, t.length());
        if (v == UNKNOWN) v = parseFix(t, 0, t.length());
        return v;
    }

    /** Finds the first ISO or FIX style timestamp inside {@code s[0,end)}, as used in log line prefixes. */
    public static long findInPrefix(String s, int end) {
        for (int i = 0; i + 17 <= end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') continue;
            if (i > 0 && isDigit(s.charAt(i - 1))) continue;
            long v = parseIso(s, i, end);
            if (v == UNKNOWN) v = parseFix(s, i, end);
            if (v != UNKNOWN) return v;
        }
        return UNKNOWN;
    }

    public static String iso(long ms) {
        return ms == UNKNOWN ? null : Instant.ofEpochMilli(ms).toString();
    }

    /** Formats as a FIX UTCTimestamp with milliseconds. */
    public static String fix(long ms) {
        long days = Math.floorDiv(ms, 86_400_000L);
        long tod = Math.floorMod(ms, 86_400_000L);
        // civil-from-days (Howard Hinnant's algorithm)
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) y++;
        StringBuilder sb = new StringBuilder(21);
        pad(sb, y, 4); pad(sb, m, 2); pad(sb, d, 2);
        sb.append('-');
        pad(sb, tod / 3_600_000, 2); sb.append(':');
        pad(sb, tod / 60_000 % 60, 2); sb.append(':');
        pad(sb, tod / 1000 % 60, 2); sb.append('.');
        pad(sb, tod % 1000, 3);
        return sb.toString();
    }

    /** ISO style with a space, used for log line prefixes: {@code 2026-01-05 09:30:00.123}. */
    public static String logPrefix(long ms) {
        String f = fix(ms);
        return f.substring(0, 4) + "-" + f.substring(4, 6) + "-" + f.substring(6, 8) + " " + f.substring(9);
    }

    private static void pad(StringBuilder sb, long v, int width) {
        String s = Long.toString(v);
        for (int i = s.length(); i < width; i++) sb.append('0');
        sb.append(s);
    }

    private static long timeOfDay(String s, int from, int to) {
        if (to - from < 8 || s.charAt(from + 2) != ':' || s.charAt(from + 5) != ':') return -1;
        int h = digits(s, from, 2), mi = digits(s, from + 3, 2), sec = digits(s, from + 6, 2);
        if (h < 0 || mi < 0 || sec < 0) return -1;
        long ms = 0;
        int p = from + 8;
        if (p < to && (s.charAt(p) == '.' || s.charAt(p) == ',')) {
            int scale = 100;
            for (p++; p < to && isDigit(s.charAt(p)); p++) {
                ms += (long) (s.charAt(p) - '0') * scale;
                scale /= 10;
            }
        }
        return h * 3_600_000L + mi * 60_000L + sec * 1000L + ms;
    }

    private static int digits(String s, int from, int n) {
        if (from + n > s.length()) return -1;
        int v = 0;
        for (int i = from; i < from + n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static long daysFromCivil(int y, int m, int d) {
        if (m <= 2) y--;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
