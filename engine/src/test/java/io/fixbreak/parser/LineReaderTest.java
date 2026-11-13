package io.fixbreak.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LineReaderTest {

    private static List<String> readAll(LineReader r) throws IOException {
        List<String> out = new ArrayList<>();
        for (String s; (s = r.next()) != null; ) out.add(s);
        return out;
    }

    @Test
    void readsLinesWithMixedTerminators() throws IOException {
        byte[] data = "one\ntwo\r\n\nlast-no-newline".getBytes(StandardCharsets.ISO_8859_1);
        try (LineReader r = new LineReader(new ByteArrayInputStream(data))) {
            assertEquals(List.of("one", "two", "", "last-no-newline"), readAll(r));
            assertEquals(4, r.lineNo());
        }
    }

    @Test
    void handlesLinesLongerThanTheBuffer() throws IOException {
        String big = "x".repeat(200_000);
        byte[] data = ("a\n" + big + "\nz\n").getBytes(StandardCharsets.ISO_8859_1);
        try (LineReader r = new LineReader(new ByteArrayInputStream(data))) {
            assertEquals(List.of("a", big, "z"), readAll(r));
        }
    }

    @Test
    void skipCountsLinesLikeNext() throws IOException {
        byte[] data = ("a\n" + "y".repeat(150_000) + "\nc\nd").getBytes(StandardCharsets.ISO_8859_1);
        try (LineReader r = new LineReader(new ByteArrayInputStream(data))) {
            assertTrue(r.skip());
            assertTrue(r.skip());
            assertEquals(2, r.lineNo());
            assertEquals("c", r.next());
            assertTrue(r.skip());
            assertEquals(4, r.lineNo());
            assertFalse(r.skip());
            assertNull(r.next());
        }
    }

    @Test
    void detectsGzip(@TempDir Path dir) throws IOException {
        Path plain = dir.resolve("a.log");
        Path gz = dir.resolve("a.log.gz");
        Files.writeString(plain, "alpha\nbeta\n");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("alpha\nbeta\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        try (LineReader a = LineReader.open(plain); LineReader b = LineReader.open(gz)) {
            assertEquals(List.of("alpha", "beta"), readAll(a));
            assertEquals(List.of("alpha", "beta"), readAll(b));
        }
    }

    @Test
    void emptyFile(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("empty.log");
        Files.writeString(empty, "");
        try (LineReader r = LineReader.open(empty)) {
            assertNull(r.next());
        }
    }
}
