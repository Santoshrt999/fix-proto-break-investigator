package io.fixbreak.parser;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Streams a log file line by line without ever holding more than one line in memory.
 * Gzip input is detected from its magic bytes. Lines are decoded as ISO-8859-1 so every
 * byte maps to exactly one char, which keeps BodyLength and CheckSum validation exact.
 *
 * <p>Logs written without line breaks (for example a FIX engine's raw message store) would
 * otherwise be one endless line. Once a line passes {@link #MAX_LINE} bytes it is broken at the
 * last {@code 8=FIX} message start, and each piece counts as a line, so memory stays bounded.
 *
 * <p>A damaged gzip stream (truncated download, unexpected end) ends the file early instead of
 * failing: {@link #damaged()} then says why, so the caller can report it.
 */
public final class LineReader implements Closeable {
    /** Longest run without a newline before the line is broken at a message boundary. */
    public static final int MAX_LINE = 1 << 16;
    /** A line with no message boundary is cut here regardless, so memory stays bounded. */
    public static final int HARD_MAX_LINE = 1 << 24;
    private static final String SKIPPED = "";

    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int pos;
    private int lim;
    private byte[] spill = new byte[4096];
    private int spillLen;
    private long lineNo;
    private boolean eof;
    private String damaged;

    public LineReader(InputStream in) {
        this.in = in;
    }

    public static LineReader open(Path file) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        raw.mark(2);
        int b0 = raw.read();
        int b1 = raw.read();
        raw.reset();
        if (b0 == 0x1f && b1 == 0x8b) return new LineReader(new GZIPInputStream(raw, 1 << 16));
        return new LineReader(raw);
    }

    /** 1-based number of the line most recently returned by {@link #next()} or consumed by {@link #skip()}. */
    public long lineNo() {
        return lineNo;
    }

    /** Why the input ended early (a damaged gzip stream), or null if it was read to the end. */
    public String damaged() {
        return damaged;
    }

    /** Next line without its terminator, or null at end of input. */
    public String next() throws IOException {
        return read(true);
    }

    /** Consumes one line without decoding it. Returns false at end of input. */
    public boolean skip() throws IOException {
        return read(false) != null;
    }

    private String read(boolean decode) throws IOException {
        while (true) {
            if (pos == lim && !fill()) {
                if (spillLen == 0) return null;
                lineNo++;
                String s = decode ? decode(spill, 0, spillLen) : SKIPPED;
                spillLen = 0;
                return s;
            }
            int nl = -1;
            for (int i = pos; i < lim; i++) {
                if (buf[i] == '\n') {
                    nl = i;
                    break;
                }
            }
            if (nl >= 0) {
                String s;
                if (spillLen == 0) {
                    s = decode ? decode(buf, pos, nl - pos) : SKIPPED;
                } else {
                    append(pos, nl);
                    s = decode ? decode(spill, 0, spillLen) : SKIPPED;
                    spillLen = 0;
                }
                pos = nl + 1;
                lineNo++;
                return s;
            }
            append(pos, lim);
            pos = lim;
            if (spillLen > MAX_LINE) {
                int cut = lastMessageStart();
                if (cut <= 0) {
                    if (spillLen <= HARD_MAX_LINE) continue; // ordinary long text line: keep reading it whole
                    cut = spillLen; // no message boundary in 16 MB: emit what we have to bound memory
                }
                lineNo++;
                String s = decode ? decode(spill, 0, cut) : SKIPPED;
                System.arraycopy(spill, cut, spill, 0, spillLen - cut);
                spillLen -= cut;
                return s;
            }
        }
    }

    /** Offset of the last "8=FIX" in the spill buffer that is not the tail of a longer tag, or -1. */
    private int lastMessageStart() {
        for (int i = spillLen - 5; i > 0; i--) {
            if (spill[i] == '8' && spill[i + 1] == '=' && spill[i + 2] == 'F' && spill[i + 3] == 'I' && spill[i + 4] == 'X'
                    && !(spill[i - 1] >= '0' && spill[i - 1] <= '9')) {
                return i;
            }
        }
        return -1;
    }

    private void append(int from, int to) {
        int add = to - from;
        if (spillLen + add > spill.length) {
            spill = java.util.Arrays.copyOf(spill, Math.max(spill.length * 2, spillLen + add));
        }
        System.arraycopy(buf, from, spill, spillLen, add);
        spillLen += add;
    }

    private static String decode(byte[] b, int off, int len) {
        if (len > 0 && b[off + len - 1] == '\r') len--;
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private boolean fill() throws IOException {
        if (eof) return false;
        int n;
        try {
            n = in.read(buf, 0, buf.length);
        } catch (EOFException | ZipException e) {
            damaged = "compressed data ended early or is corrupt (" + e.getMessage() + "); the rest of the file was not read";
            n = -1;
        }
        if (n <= 0) {
            eof = true;
            return false;
        }
        pos = 0;
        lim = n;
        return true;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
