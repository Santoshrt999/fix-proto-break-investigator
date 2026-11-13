package io.fixbreak;

import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import io.fixbreak.gen.SampleGenerator;
import io.fixbreak.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Half a million messages must scan in seconds, not minutes. */
class PerformanceSmokeTest {
    private static final long BUDGET_MILLIS = 30_000; // generous: CI runners are slow; a laptop needs about a second

    @Test
    void scansHalfAMillionMessagesQuickly(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("big.log");
        SampleGenerator.Options opt = new SampleGenerator.Options();
        opt.orders = 110_000;
        opt.plant = false;
        SampleGenerator gen = new SampleGenerator(opt);
        try (OutputStream out = Files.newOutputStream(log)) {
            gen.generate(out);
        }
        assertTrue(gen.linesWritten() >= 500_000, "generated only " + gen.linesWritten() + " messages");

        long start = System.nanoTime();
        ScanEngine engine = new ScanEngine(new ScanConfig(), Rules.defaults());
        engine.scan(List.of(log));
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("PerformanceSmokeTest: %,d messages, %,d MB, %,d orders scanned in %,d ms%n",
                engine.messages, Files.size(log) >> 20, engine.book.orders().size(), millis);
        assertEquals(gen.linesWritten(), engine.messages);
        assertEquals(opt.orders, engine.book.orders().size());
        assertEquals(0, engine.findings.total(), () -> "healthy flow must scan clean, got " + engine.findings.countByRule());
        assertTrue(millis < BUDGET_MILLIS, "scan took " + millis + " ms");
    }
}
