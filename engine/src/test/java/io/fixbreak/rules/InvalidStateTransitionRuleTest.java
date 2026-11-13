package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvalidStateTransitionRuleTest extends RuleTest {
    @Test
    void fillAfterCanceled() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.ack("A1", 1000);
        fix.fill("A1", 1000, 400, 400);
        fix.exec("A1", null, '4', '4', 1000, 0, 400, 0);
        fix.exec("A1", null, 'F', '1', 1000, 100, 500, 500);
        Finding f = single("INVALID_STATE_TRANSITION");
        assertEquals(Severity.CRITICAL, f.severity);
        assertTrue(f.summary.contains("received a fill (LastQty(32)=100, CumQty(14)=500) after it was already Canceled"), f.summary);
        // the canceled order stays canceled, so it is not also reported as stuck
        assertRules("INVALID_STATE_TRANSITION");
    }

    @Test
    void fillAfterFilled() {
        cleanOrder("A1");
        fix.exec("A1", null, 'F', '2', 100, 10, 100, 0);
        Finding f = single("INVALID_STATE_TRANSITION");
        assertTrue(f.summary.contains("after it was already Filled"), f.summary);
    }

    @Test
    void filledBackToNew() {
        cleanOrder("A1");
        fix.exec("A1", null, '0', '0', 100, 0, 100, 0);
        Finding f = single("INVALID_STATE_TRANSITION");
        assertEquals(Severity.HIGH, f.severity);
        assertTrue(f.summary.contains("went from Filled to New"), f.summary);
    }

    @Test
    void cumQtyGoingDown() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.fill("A1", 1000, 400, 400);
        fix.exec("A1", null, '0', '1', 1000, 0, 300, 700);
        Finding f = single("INVALID_STATE_TRANSITION");
        assertTrue(f.summary.contains("CumQty(14) went down from 400 to 300"), f.summary);
    }

    @Test
    void tradeCancelAfterFilledIsLegitimate() {
        cleanOrder("A1");
        fix.exec("A1", null, 'H', '1', 100, 40, 60, 40);
        fix.exec("A1", null, 'F', '2', 100, 40, 100, 0);
        assertNone("INVALID_STATE_TRANSITION");
    }

    @Test
    void normalLifecyclesAreClean() {
        cleanOrder("A1");
        fix.newOrder("A2", "ACME", 100, "10.00");
        fix.exec("A2", null, 'A', 'A', 100, 0, 0, 100);
        fix.ack("A2", 100);
        fix.fill("A2", 100, 50, 50);
        fix.out("F", 11, "A3", 41, "A2", 55, "ACME", 54, 1);
        fix.exec("A3", "A2", '6', '6', 100, 0, 50, 50);
        fix.exec("A3", "A2", '4', '4', 100, 0, 50, 0);
        assertTrue(scan().isEmpty());
    }
}
