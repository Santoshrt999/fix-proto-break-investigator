package io.fixbreak.parser;

/**
 * Extracts a FIX message from one log line.
 *
 * <p>Handles SOH (0x01), {@code |} and the two-character {@code ^A} as field delimiters, and any
 * prefix before {@code 8=FIX} (timestamps, direction markers, logger names). BodyLength(9) and
 * CheckSum(10) are validated as if the delimiter were SOH, which is what the sender computed
 * them over. Problems are reported through {@link Result#issue} rather than exceptions.
 *
 * <p>Lines must be decoded as ISO-8859-1 so that one char is one byte (see {@link LineReader}).
 */
public final class FixLineParser {

    public enum Issue { BAD_CHECKSUM, BAD_BODY_LENGTH, TRUNCATED, MALFORMED }

    /** Outcome of parsing one line. Reused between calls to avoid allocation. */
    public static final class Result {
        /** The parsed message; may be partial when {@link #issue} is TRUNCATED or MALFORMED. Null if no field parsed. */
        public FixMessage msg;
        /** Null when the message is clean. */
        public Issue issue;
        public String detail;
        /** True when the message has enough structure (MsgType present, not cut short) to feed the order state machine. */
        public boolean complete;
        /** Where to look for another message on the same line, or -1 when the line is used up. */
        public int next = -1;
    }

    private int[] tags = new int[64];
    private int[] vs = new int[64];
    private int[] ve = new int[64];

    /** Parses the first message on the line. Returns false when the line contains no FIX message at all. */
    public boolean parse(String line, int fileIdx, long lineNo, Result out) {
        return parse(line, 0, 0, fileIdx, lineNo, out);
    }

    /**
     * Parses the next message on the line, starting the search at {@code from}. {@code part} is
     * the message's position on the line (0 for the first). Returns false when there is no
     * further FIX message; {@link Result#next} says where to continue.
     */
    public boolean parse(String line, int from, int part, int fileIdx, long lineNo, Result out) {
        out.msg = null;
        out.issue = null;
        out.detail = null;
        out.complete = false;
        out.next = -1;
        if (from < 0 || from >= line.length()) return false;

        int start = line.indexOf("8=FIX", from);
        while (start > 0 && isDigit(line.charAt(start - 1))) {
            start = line.indexOf("8=FIX", start + 1); // e.g. "108=FIX..." inside some other text
        }
        if (start < 0) return false;

        final int n = line.length();
        int i = start + 5;
        while (i < n && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '.')) i++;
        if (i >= n) {
            out.issue = Issue.TRUNCATED;
            out.detail = "line ends inside BeginString";
            return true;
        }
        final char delim;
        final int delimLen;
        char c = line.charAt(i);
        if (c == '\u0001') {
            delim = '\u0001';
            delimLen = 1;
        } else if (c == '|') {
            delim = '|';
            delimLen = 1;
        } else if (c == '^' && i + 1 < n && line.charAt(i + 1) == 'A') {
            delim = '^';
            delimLen = 2;
        } else {
            out.issue = Issue.MALFORMED;
            out.detail = "unrecognised field delimiter after BeginString";
            out.next = nextStart(line, start + 5);
            return true;
        }

        int count = 0;
        int pos = start;
        int checksum = 0;
        int bodyLen = 0;
        boolean sawChecksum = false;
        boolean cutShort = false;

        while (pos < n) {
            int tag = 0;
            int p = pos;
            while (p < n && isDigit(line.charAt(p)) && p - pos < 9) {
                tag = tag * 10 + (line.charAt(p) - '0');
                p++;
            }
            if (p == pos || p >= n || line.charAt(p) != '=') {
                cutShort = true; // partial or garbage tag: the line was cut or corrupted here
                break;
            }
            if (tag == Tags.BEGIN_STRING && count > 0) {
                cutShort = true; // a new message starts before this one reached its CheckSum
                out.next = pos;
                break;
            }
            int valStart = p + 1;
            int end;
            if (tag == Tags.CHECK_SUM) {
                end = valStart;
                while (end < n && end - valStart < 3 && isDigit(line.charAt(end))) end++;
                sawChecksum = true;
            } else {
                end = findDelimiter(line, valStart, delim, delimLen);
                if (end < 0) {
                    cutShort = true; // value runs to end of line without a delimiter
                    break;
                }
                for (int k = pos; k < end; k++) checksum += line.charAt(k);
                checksum += 1;
                if (count >= 2) bodyLen += end - pos + 1;
            }
            if (count == tags.length) grow();
            tags[count] = tag;
            vs[count] = valStart;
            ve[count] = end;
            count++;
            if (sawChecksum) break;
            pos = end + delimLen;
        }

        if (sawChecksum) out.next = ve[count - 1];
        else if (out.next < 0 && cutShort) out.next = nextStart(line, pos);
        if (count == 0) {
            out.issue = Issue.TRUNCATED;
            out.detail = "no complete field";
            return true;
        }
        FixMessage msg = new FixMessage(line, start,
                java.util.Arrays.copyOf(tags, count), java.util.Arrays.copyOf(vs, count),
                java.util.Arrays.copyOf(ve, count), count, fileIdx, lineNo, part);
        out.msg = msg;
        boolean hasMsgType = msg.has(Tags.MSG_TYPE);

        if (!sawChecksum || cutShort) {
            out.issue = Issue.TRUNCATED;
            out.detail = "message ends after tag " + tags[count - 1] + " with no CheckSum(10)";
            return true;
        }
        if (count < 3 || tags[1] != Tags.BODY_LENGTH || !hasMsgType) {
            out.issue = Issue.MALFORMED;
            out.detail = !hasMsgType ? "MsgType(35) missing" : "BodyLength(9) is not the second field";
            return true;
        }
        out.complete = true;
        long declaredLen = msg.getLong(Tags.BODY_LENGTH, -1);
        if (declaredLen != bodyLen) {
            out.issue = Issue.BAD_BODY_LENGTH;
            out.detail = "BodyLength(9)=" + msg.get(Tags.BODY_LENGTH) + " but body is " + bodyLen + " bytes";
            return true;
        }
        long declaredSum = msg.getLong(Tags.CHECK_SUM, -1);
        int actual = checksum & 0xFF;
        if (declaredSum != actual || ve[count - 1] - vs[count - 1] != 3) {
            out.issue = Issue.BAD_CHECKSUM;
            out.detail = "CheckSum(10)=" + msg.get(Tags.CHECK_SUM) + " but computed " + String.format("%03d", actual);
        }
        return true;
    }

    /**
     * Index of the next field delimiter at or after {@code from}, or -1. For the printable
     * delimiters a candidate only counts when followed by {@code digits=} or end of line, so a
     * stray '|' inside Text(58) does not split the field.
     */
    private static int findDelimiter(String line, int from, char delim, int delimLen) {
        if (delim == '\u0001') return line.indexOf('\u0001', from);
        final int n = line.length();
        int idx = from;
        while (true) {
            idx = line.indexOf(delim, idx);
            if (idx < 0) return -1;
            if (delimLen == 2 && (idx + 1 >= n || line.charAt(idx + 1) != 'A')) {
                idx++;
                continue;
            }
            int j = idx + delimLen;
            if (j >= n) return idx;
            int k = j;
            while (k < n && isDigit(line.charAt(k))) k++;
            if (k > j && (k == n || line.charAt(k) == '=')) return idx; // k == n: line cut inside the next tag
            // trailing whitespace after the final delimiter also ends the field
            if (k == j && line.substring(j).isBlank()) return idx;
            idx++;
        }
    }

    /** Start of the next "8=FIX" at or after {@code from} that is not part of a longer tag, or -1. */
    private static int nextStart(String line, int from) {
        int s = line.indexOf("8=FIX", Math.max(0, from));
        while (s > 0 && isDigit(line.charAt(s - 1))) s = line.indexOf("8=FIX", s + 1);
        return s;
    }

    private void grow() {
        int cap = tags.length * 2;
        tags = java.util.Arrays.copyOf(tags, cap);
        vs = java.util.Arrays.copyOf(vs, cap);
        ve = java.util.Arrays.copyOf(ve, cap);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
