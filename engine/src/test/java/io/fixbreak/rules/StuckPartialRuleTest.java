package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StuckPartialRuleTest extends RuleTest {
    @Test
    void partialFillWithNoTerminalState() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.ack("A1", 1000);
        fix.fill("A1", 1000, 300, 300);
        fix.after(600_000).out("0");
        Finding f = single("STUCK_PARTIAL");
        assertEquals(Severity.MEDIUM, f.severity);
        assertTrue(f.summary.contains("partially filled 300/1000"), f.summary);
        assertTrue(f.summary.contains("LeavesQty 700"), f.summary);
        assertTrue(f.summary.contains("no activity for the last 10m"), f.summary);
        assertRules("STUCK_PARTIAL");
    }

    @Test
    void trickyChainCancelRejectedThenPartiallyFilled() {
        fix.newOrder("T1", "ACME", 1000, "50.00");
        fix.ack("T1", 1000);
        fix.out("G", 11, "T2", 41, "T1", 55, "ACME", 54, 1, 38, 1500, 40, 2, 44, "50.00");
        fix.exec("T2", "T1", '5', '0', 1500, 0, 0, 1500);
        fix.out("G", 11, "T3", 41, "T2", 55, "ACME", 54, 1, 38, 1500, 40, 2, 44, "50.10");
        fix.exec("T3", "T2", '5', '0', 1500, 0, 0, 1500);
        fix.out("F", 11, "T4", 41, "T3", 55, "ACME", 54, 1);
        fix.in("9", 37, "BX-1", 11, "T4", 41, "T3", 39, 0, 434, 1, 102, 0, 58, "Too late to cancel");
        fix.exec("T3", null, 'F', '1', 1500, 600, 600, 900);
        assertRules("STUCK_PARTIAL", "CANCEL_REPLACE_REJECTED");
        Finding f = single("STUCK_PARTIAL");
        assertEquals(List.of("T1", "T2", "T3", "T4"), f.clOrdIds);
        assertTrue(f.summary.contains("600/1500"), f.summary);
    }

    @Test
    void filledCanceledAndExpiredOrdersAreNotStuck() {
        cleanOrder("A1");
        fix.newOrder("A2", "ACME", 100, "10.00");
        fix.fill("A2", 100, 40, 40);
        fix.exec("A2", null, '4', '4', 100, 0, 40, 0);
        fix.newOrder("A3", "ACME", 100, "10.00");
        fix.fill("A3", 100, 40, 40);
        fix.exec("A3", null, 'C', 'C', 100, 0, 40, 0);
        assertTrue(scan().isEmpty());
    }

    @Test
    void unfilledOpenOrderIsNotStuckPartial() {
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.ack("A1", 100);
        assertNone("STUCK_PARTIAL");
    }
}
