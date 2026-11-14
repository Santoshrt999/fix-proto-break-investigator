package io.fixbreak.parser;

/**
 * A log line reference (file index + 1-based line number) packed into one long, so trackers
 * can remember where evidence lives without holding on to message text. 0 means "no reference".
 */
public final class Refs {
    private Refs() {}

    public static long of(int fileIdx, long line) {
        return ((long) fileIdx << 40) | line;
    }

    public static int file(long ref) {
        return (int) (ref >>> 40);
    }

    public static long line(long ref) {
        return ref & 0xFFFFFFFFFFL;
    }
}
