package io.fixbreak;

import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import io.fixbreak.gen.SampleGenerator;
import io.fixbreak.rules.Finding;
import io.fixbreak.rules.Rules;
import io.fixbreak.util.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The committed sample log must produce exactly the committed expected findings. */
class GoldenSampleTest {
    static final Path SAMPLES = Path.of("..", "samples");
    static final Path LOG = SAMPLES.resolve("day1.log");
    static final Path EXPECTED = SAMPLES.resolve("expected-findings.json");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> expected() throws IOException {
        Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(EXPECTED));
        return (List<Map<String, Object>>) root.get("findings");
    }

    private static List<Finding> scan() throws IOException {
        ScanEngine engine = new ScanEngine(new ScanConfig(), Rules.defaults());
        engine.scan(List.of(LOG));
        return engine.findings.sorted();
    }

    @Test
    void scanFindsExactlyThePlantedBreaks() throws IOException {
        List<String> want = new ArrayList<>();
        for (Map<String, Object> e : expected()) want.add(e.get("ruleId") + " " + e.get("clOrdId"));
        List<String> got = new ArrayList<>();
        for (Finding f : scan()) got.add(f.ruleId + " " + f.rootClOrdId());
        Collections.sort(want);
        Collections.sort(got);
        assertEquals(want, got);
    }

    @Test
    void sessionsMatchToo() throws IOException {
        List<String> want = new ArrayList<>();
        for (Map<String, Object> e : expected()) want.add(e.get("ruleId") + " " + e.get("clOrdId") + " " + e.get("session"));
        List<String> got = new ArrayList<>();
        for (Finding f : scan()) got.add(f.ruleId + " " + f.rootClOrdId() + " " + f.session);
        Collections.sort(want);
        Collections.sort(got);
        assertEquals(want, got);
    }

    @Test
    void everyPhaseOneRuleIsPlantedAtLeastOnce() throws IOException {
        Set<String> planted = new TreeSet<>();
        for (Map<String, Object> e : expected()) planted.add((String) e.get("ruleId"));
        assertEquals(new TreeSet<>(List.of(
                "ORDER_REJECTED", "SESSION_REJECT", "BUSINESS_REJECT", "CANCEL_REPLACE_REJECTED", "NO_ACK",
                "STUCK_PARTIAL", "OVERFILL", "QTY_MISMATCH", "DUPLICATE_EXEC", "BROKEN_CHAIN", "SEQ_GAP",
                "INVALID_STATE_TRANSITION", "PARSE_ERROR")), planted);
    }

    @Test
    void trickyAmendChainIsCancelRejectedThenStuckPartial() throws IOException {
        List<Finding> chain = scan().stream().filter(f -> f.clOrdIds.size() == 4).toList();
        assertEquals(Set.of("CANCEL_REPLACE_REJECTED", "STUCK_PARTIAL"),
                Set.copyOf(chain.stream().map(f -> f.ruleId).toList()));
    }

    @Test
    void everyFindingHasEvidenceWithinTheCap() throws IOException {
        for (Finding f : scan()) {
            assertTrue(f.evidence.length >= 1 && f.evidence.length <= Finding.MAX_EVIDENCE, f.id);
            assertFalse(f.summary.isBlank(), f.id);
        }
    }

    @Test
    void committedSampleIsReproducibleFromTheSeed() throws IOException {
        SampleGenerator.Options opt = new SampleGenerator.Options();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SampleGenerator(opt).generate(out);
        assertArrayEquals(Files.readAllBytes(LOG), out.toByteArray(),
                "samples/day1.log is out of date: run bin/fixscan generate and commit the result");
    }
}
