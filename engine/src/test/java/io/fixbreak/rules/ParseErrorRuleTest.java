package io.fixbreak.rules;

import io.fixbreak.parser.FixLineParser.Issue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParseErrorRuleTest extends RuleTest {
    @Test
    void groupsBadChecksumsIntoOneFinding() {
        fix.logon();
        for (int i = 0; i < 8; i++) {
            fix.out("0");
            int last = fix.lines().size() - 1;
            String good = fix.lines().get(last);
            String bad = good.replaceAll("10=\\d{3}", good.contains("10=000") ? "10=001" : "10=000");
            fix.lines().set(last, bad);
        }
        Finding f = single("PARSE_ERROR");
        assertEquals(Severity.LOW, f.severity);
        assertTrue(f.summary.startsWith("8 lines with a wrong CheckSum(10) in test.log"), f.summary);
        assertEquals(Finding.MAX_EVIDENCE, f.evidence.length);
        assertEquals(8, engine.issueCount(Issue.BAD_CHECKSUM));
        // bad-checksum messages are still analysed, so there is no sequence gap
        assertRules("PARSE_ERROR");
    }

    @Test
    void truncatedLineKeepsSequenceTrackingIntact() {
        fix.logon();
        fix.out("0");
        fix.in("0");
        int last = fix.lines().size() - 1;
        String full = fix.lines().get(last);
        fix.lines().set(last, full.substring(0, full.indexOf("52=") + 12));
        fix.in("0");
        Finding f = single("PARSE_ERROR");
        assertTrue(f.summary.startsWith("1 line cut short before CheckSum(10)"), f.summary);
        assertRules("PARSE_ERROR");
    }

    @Test
    void nonFixLinesAreCountedNotReported() {
        fix.addLine("2026-01-05 09:30:00 INFO starting session");
        fix.addLine("");
        cleanOrder("A1");
        assertTrue(scan().isEmpty());
        assertEquals(1, engine.nonFixLines);
        assertEquals(3, engine.messages);
    }
}
