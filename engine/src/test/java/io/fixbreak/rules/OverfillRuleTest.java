package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverfillRuleTest extends RuleTest {
    @Test
    void cumQtyAboveOrderQty() {
        fix.newOrder("A1", "ACME", 500, "10.00");
        fix.ack("A1", 500);
        fix.fill("A1", 500, 300, 300);
        fix.exec("A1", null, 'F', '2', 500, 300, 600, 0);
        Finding f = single("OVERFILL");
        assertEquals(Severity.CRITICAL, f.severity);
        assertTrue(f.summary.contains("CumQty(14)=600 exceeds OrderQty(38)=500 by 100"), f.summary);
        assertRules("OVERFILL");
    }

    @Test
    void reportedOncePerOrder() {
        fix.newOrder("A1", "ACME", 500, "10.00");
        fix.exec("A1", null, 'F', '1', 500, 600, 600, 0);
        fix.exec("A1", null, 'F', '1', 500, 100, 700, 0);
        assertEquals(1, of("OVERFILL").size());
    }

    @Test
    void amendUpThenFillIsNotAnOverfill() {
        fix.newOrder("A1", "ACME", 500, "10.00");
        fix.ack("A1", 500);
        fix.out("G", 11, "A2", 41, "A1", 55, "ACME", 54, 1, 38, 800, 40, 2, 44, "10.00");
        fix.exec("A2", "A1", '5', '0', 800, 0, 0, 800);
        fix.fill("A2", 800, 800, 800);
        assertTrue(scan().isEmpty());
    }
}
