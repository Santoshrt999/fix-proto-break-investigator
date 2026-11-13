package io.fixbreak.parser;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Streams a log file line by line without ever holding more than one line in memory.
 * Gzip input is detected from its magic bytes. Lines are decoded as ISO-8859-1 so every
 * byte maps to exactly one char, which keeps BodyLength and CheckSum validation exact.
 */
public final class LineReader implements Closeable {
    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int pos;
    private int lim;
    private byte[] spill = new byte[4096];
    private long lineNo;
    private boolean eof;

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

    /** Next line without its terminator, or null at end of input. */
    public String next() throws IOException {
        int spillLen = 0;
        while (true) {
            if (pos == lim && !fill()) {
                if (spillLen == 0) return null;
                lineNo++;
                return decode(spill, 0, spillLen);
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
                    s = decode(buf, pos, nl - pos);
                } else {
                    spillLen = append(spillLen, pos, nl);
                    s = decode(spill, 0, spillLen);
                }
                pos = nl + 1;
                lineNo++;
                return s;
            }
            spillLen = append(spillLen, pos, lim);
            pos = lim;
        }
    }

    /** Consumes one line without decoding it. Returns false at end of input. */
    public boolean skip() throws IOException {
        boolean any = false;
        while (true) {
            if (pos == lim && !fill()) {
                if (any) lineNo++;
                return any;
            }
            for (int i = pos; i < lim; i++) {
                if (buf[i] == '\n') {
                    pos = i + 1;
                    lineNo++;
                    return true;
                }
            }
            any = true;
            pos = lim;
        }
    }

    private int append(int spillLen, int from, int to) {
        int add = to - from;
        if (spillLen + add > spill.length) {
            spill = java.util.Arrays.copyOf(spill, Math.max(spill.length * 2, spillLen + add));
        }
        System.arraycopy(buf, from, spill, spillLen, add);
        return spillLen + add;
    }

    private static String decode(byte[] b, int off, int len) {
        if (len > 0 && b[off + len - 1] == '\r') len--;
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private boolean fill() throws IOException {
        if (eof) return false;
        int n = in.read(buf, 0, buf.length);
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
