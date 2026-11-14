package io.fixbreak.engine;

import io.fixbreak.mask.Masker;
import io.fixbreak.parser.FixLineParser;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.LineReader;
import io.fixbreak.parser.Refs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Turns line references back into log lines. The scan itself keeps only references; this
 * re-reads just the lines that will actually be shown, skipping everything else without
 * decoding it.
 */
public final class EvidenceResolver {
    /** One evidence line, parsed again so it can be masked and decoded. */
    public record Line(long ref, String file, long line, String raw, FixMessage msg) {
        /** {@code day1.log:412} */
        public String location() {
            return file + ":" + line;
        }
    }

    private final List<Path> files;
    private final Map<Long, Line> cache = new HashMap<>();

    public EvidenceResolver(List<Path> files) {
        this.files = files;
    }

    /** Loads every requested line that is not loaded yet. One sequential pass per file, stopping at the last needed line. */
    public void load(Collection<Long> refs) throws IOException {
        Map<Integer, TreeSet<Long>> wanted = new HashMap<>();
        for (long ref : refs) {
            if (ref != 0 && !cache.containsKey(ref)) {
                wanted.computeIfAbsent(Refs.file(ref), k -> new TreeSet<>()).add(Refs.line(ref));
            }
        }
        FixLineParser parser = new FixLineParser();
        FixLineParser.Result res = new FixLineParser.Result();
        for (Map.Entry<Integer, TreeSet<Long>> e : wanted.entrySet()) {
            int fileIdx = e.getKey();
            Path path = files.get(fileIdx);
            String name = path.getFileName().toString();
            try (LineReader reader = LineReader.open(path)) {
                for (long target : e.getValue()) {
                    while (reader.lineNo() < target - 1) {
                        if (!reader.skip()) break;
                    }
                    String raw = reader.next();
                    if (raw == null) break;
                    parser.parse(raw, fileIdx, target, res);
                    cache.put(Refs.of(fileIdx, target), new Line(Refs.of(fileIdx, target), name, target, raw, res.msg));
                }
            }
        }
    }

    public Line get(long ref) {
        return cache.get(ref);
    }

    /** Masked {@code tag=value|...} text for a loaded line. Lines that did not parse are never echoed raw. */
    public String excerpt(long ref, Masker masker, int maxLen) {
        Line l = cache.get(ref);
        if (l == null) return null;
        if (l.msg() == null) return "<unparseable line, " + l.raw().length() + " chars>";
        return masker.excerpt(l.msg(), maxLen);
    }
}
