package io.fixbreak.parser;

import io.fixbreak.util.Times;

/**
 * One parsed FIX message. Field values are kept as offsets into the original log line and
 * only turned into strings on demand, so parsing millions of messages stays cheap.
 */
public final class FixMessage {
    private final String raw;
    private final int start;
    private final int[] tags;
    private final int[] valueStart;
    private final int[] valueEnd;
    private final int count;
    private final int fileIdx;
    private final long line;
    private final int part;
    private long time = Times.UNKNOWN;
    private boolean timeParsed;

    FixMessage(String raw, int start, int[] tags, int[] vs, int[] ve, int count, int fileIdx, long line, int part) {
        this.raw = raw;
        this.start = start;
        this.tags = tags;
        this.valueStart = vs;
        this.valueEnd = ve;
        this.count = count;
        this.fileIdx = fileIdx;
        this.line = line;
        this.part = part;
    }

    public int fieldCount() {
        return count;
    }

    public int tagAt(int i) {
        return tags[i];
    }

    public String valueAt(int i) {
        return raw.substring(valueStart[i], valueEnd[i]);
    }

    private int indexOf(int tag) {
        for (int i = 0; i < count; i++) {
            if (tags[i] == tag) return i;
        }
        return -1;
    }

    public boolean has(int tag) {
        return indexOf(tag) >= 0;
    }

    /** First value of the tag, or null when absent. */
    public String get(int tag) {
        int i = indexOf(tag);
        return i < 0 ? null : raw.substring(valueStart[i], valueEnd[i]);
    }

    /** First character of the tag's value, or 0 when absent or empty. Handy for single-char enums. */
    public char getChar(int tag) {
        int i = indexOf(tag);
        return i < 0 || valueEnd[i] == valueStart[i] ? 0 : raw.charAt(valueStart[i]);
    }

    public boolean is(int tag, char value) {
        int i = indexOf(tag);
        return i >= 0 && valueEnd[i] - valueStart[i] == 1 && raw.charAt(valueStart[i]) == value;
    }

    public long getLong(int tag, long dflt) {
        int i = indexOf(tag);
        if (i < 0) return dflt;
        int s = valueStart[i], e = valueEnd[i];
        if (s == e || e - s > 18) return dflt;
        long v = 0;
        for (int p = s; p < e; p++) {
            char c = raw.charAt(p);
            if (c < '0' || c > '9') return dflt;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /** Numeric value of the tag, or NaN when absent or not a number. */
    public double getDouble(int tag) {
        int i = indexOf(tag);
        if (i < 0) return Double.NaN;
        int s = valueStart[i], e = valueEnd[i];
        // fast path for plain non-negative integers, the common case for quantities
        long v = 0;
        boolean plain = e > s && e - s <= 15;
        for (int p = s; plain && p < e; p++) {
            char c = raw.charAt(p);
            if (c < '0' || c > '9') plain = false;
            else v = v * 10 + (c - '0');
        }
        if (plain) return v;
        try {
            double d = Double.parseDouble(raw.substring(s, e));
            return Double.isFinite(d) ? d : Double.NaN; // "NaN", "Infinity" and overflow are not quantities
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    public String msgType() {
        return get(Tags.MSG_TYPE);
    }

    /** SendingTime(52) if present, otherwise the timestamp from the log line prefix. */
    public long time() {
        if (!timeParsed) {
            timeParsed = true;
            int i = indexOf(Tags.SENDING_TIME);
            if (i >= 0) time = Times.parseFix(raw, valueStart[i], valueEnd[i]);
            if (time == Times.UNKNOWN && start > 0) time = Times.findInPrefix(raw, start);
        }
        return time;
    }

    public int fileIdx() {
        return fileIdx;
    }

    public long line() {
        return line;
    }

    /** Compact reference to the log line this message came from. */
    public long ref() {
        return Refs.of(fileIdx, line, part);
    }
}
