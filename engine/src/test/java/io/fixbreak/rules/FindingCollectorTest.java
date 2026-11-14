package io.fixbreak.rules;

import io.fixbreak.util.Times;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FindingCollectorTest extends RuleTest {
    private void threeBreaks() {
        fix.logon();
        fix.newOrder("A1", "ACME", 500, "10.00");          // overfill (CRITICAL)
        fix.exec("A1", null, 'F', '2', 500, 600, 600, 0);
        fix.after(60_000);
        fix.newOrder("A2", "ACME", 100, "10.00");          // rejected (MEDIUM)
        fix.exec("A2", null, '8', '8', 100, 0, 0, 0);
        fix.send("C2", "B2", "D", 11, "Z1", 55, "ACME", 54, 1, 38, 10, 40, 1);   // other session, rejected
        fix.send("B2", "C2", "8", 37, "O", 11, "Z1", 17, "ZE1", 150, 8, 39, 8, 38, 10, 151, 0, 14, 0, 6, 0);
    }

    @Test
    void sortsMostSevereFirstAndCounts() {
        threeBreaks();
        List<Finding> all = scan();
        assertEquals(3, all.size());
        assertEquals("OVERFILL", all.get(0).ruleId);
        assertEquals("OVERFILL-1", all.get(0).id);
        assertEquals(3, engine.findings.total());
        assertEquals(2L, engine.findings.countByRule().get("ORDER_REJECTED"));
        assertEquals(1L, engine.findings.countBySeverity().get(Severity.CRITICAL));
    }

    @Test
    void filtersBySeveritySessionRuleAndTime() {
        threeBreaks();
        config.minSeverity = Severity.HIGH;
        assertEquals(List.of("OVERFILL"), scan().stream().map(f -> f.ruleId).toList());

        config.minSeverity = Severity.INFO;
        config.sessionFilter = "B2-C2";
        List<Finding> s = scan();
        assertEquals(1, s.size());
        assertEquals("Z1", s.get(0).rootClOrdId());
        assertEquals("ORDER_REJECTED-2", s.get(0).id, "ids do not change with filters");

        config.sessionFilter = null;
        config.ruleFilter = Set.of("ORDER_REJECTED");
        assertEquals(2, scan().size());

        config.ruleFilter = null;
        config.until = Times.parseLoose("2026-01-05T09:30:30");
        assertEquals(List.of("OVERFILL"), scan().stream().map(f -> f.ruleId).toList());
        config.until = Times.UNKNOWN;
        config.since = Times.parseLoose("2026-01-05T09:30:30");
        assertEquals(2, scan().size());
    }

    @Test
    void storageCapKeepsCountsExact() {
        config.maxStoredPerRule = 3;
        for (int i = 0; i < 10; i++) {
            fix.newOrder("R" + i, "ACME", 100, "10.00");
            fix.exec("R" + i, null, '8', '8', 100, 0, 0, 0);
        }
        assertEquals(3, scan().size());
        assertEquals(10, engine.findings.total());
        assertEquals(10L, engine.findings.countByRule().get("ORDER_REJECTED"));
    }
}
