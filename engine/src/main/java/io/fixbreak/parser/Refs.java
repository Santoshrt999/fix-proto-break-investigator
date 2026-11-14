package io.fixbreak.parser;

/**
 * A log reference packed into one long: file index (12 bits), 1-based line number (40 bits) and
 * the message's position on that line (12 bits, 0 for the first or only message). Trackers keep
 * these instead of message text. 0 means "no reference".
 */
public final class Refs {
    private Refs() {}

    /** Highest message position on one line that can be told apart. */
    public static final int MAX_PART = 0xFFF;

    public static long of(int fileIdx, long line) {
        return of(fileIdx, line, 0);
    }

    public static long of(int fileIdx, long line, int part) {
        return ((long) fileIdx << 52) | ((line & 0xFFFFFFFFFFL) << 12) | Math.min(part, MAX_PART);
    }

    public static int file(long ref) {
        return (int) (ref >>> 52);
    }

    public static long line(long ref) {
        return (ref >>> 12) & 0xFFFFFFFFFFL;
    }

    public static int part(long ref) {
        return (int) (ref & MAX_PART);
    }
}
