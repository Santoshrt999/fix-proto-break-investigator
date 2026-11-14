package io.fixbreak.rules;

import io.fixbreak.Fix;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Base for rule tests: script a conversation with {@link Fix}, scan it, inspect the findings. */
abstract class RuleTest {
    @TempDir
    Path dir;

    protected final Fix fix = new Fix();
    protected final ScanConfig config = new ScanConfig();
    protected ScanEngine engine;

    protected List<Finding> scan() {
        try {
            Path log = dir.resolve("test.log");
            Files.write(log, fix.lines(), StandardCharsets.ISO_8859_1);
            engine = new ScanEngine(config, Rules.defaults());
            engine.scan(List.of(log));
            return engine.findings.sorted();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected List<Finding> of(String ruleId) {
        return scan().stream().filter(f -> f.ruleId.equals(ruleId)).toList();
    }

    protected Finding single(String ruleId) {
        List<Finding> l = of(ruleId);
        assertEquals(1, l.size(), () -> ruleId + " findings: " + l.stream().map(f -> f.summary).toList());
        return l.get(0);
    }

    protected void assertNone(String ruleId) {
        List<Finding> l = of(ruleId);
        assertEquals(0, l.size(), () -> "unexpected " + ruleId + ": " + l.stream().map(f -> f.summary).toList());
    }

    /** Asserts the scan produced findings from exactly these rules and no others. */
    protected void assertRules(String... ruleIds) {
        Set<String> actual = scan().stream().map(f -> f.ruleId).collect(Collectors.toSet());
        assertEquals(Set.of(ruleIds), actual);
    }

    /** A clean order: new, acknowledged, fully filled. */
    protected void cleanOrder(String id) {
        fix.newOrder(id, "ACME", 100, "10.00");
        fix.ack(id, 100);
        fix.fill(id, 100, 100, 100);
    }
}
