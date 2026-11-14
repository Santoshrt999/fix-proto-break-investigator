package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QtyMismatchRuleTest extends RuleTest {
    @Test
    void cumQtyDoesNotMatchSumOfFills() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.ack("A1", 1000);
        fix.fill("A1", 1000, 200, 200);
        fix.exec("A1", null, 'F', '1', 1000, 300, 600, 400); // 200 + 300 is 500, not 600
        fix.exec("A1", null, 'F', '2', 1000, 400, 1000, 0);
        Finding f = single("QTY_MISMATCH");
        assertEquals(Severity.HIGH, f.severity);
        assertTrue(f.summary.contains("CumQty(14)=600 but the fills reported so far (LastQty(32)) add up to 500"), f.summary);
        assertRules("QTY_MISMATCH");
    }

    @Test
    void leavesQtyDoesNotMatch() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.ack("A1", 1000);
        fix.exec("A1", null, 'F', '1', 1000, 200, 200, 700);
        fix.exec("A1", null, 'F', '2', 1000, 800, 1000, 0);
        Finding f = single("QTY_MISMATCH");
        assertTrue(f.summary.contains("LeavesQty(151)=700 but OrderQty(38) - CumQty(14) = 1000 - 200 = 800"), f.summary);
    }

    @Test
    void orderSeenFromMidLifeIsNotCheckedAgainstFillSum() {
        fix.exec("MID", null, 'F', '1', 1000, 100, 700, 300);
        fix.exec("MID", null, 'F', '2', 1000, 300, 1000, 0);
        assertNone("QTY_MISMATCH");
    }

    @Test
    void canceledOrderWithZeroLeavesIsConsistent() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.fill("A1", 1000, 200, 200);
        fix.exec("A1", null, '4', '4', 1000, 0, 200, 0);
        assertTrue(scan().isEmpty());
    }

    @Test
    void tradeCancelKeepsTheBooksBalanced() {
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.fill("A1", 100, 60, 60);
        fix.exec("A1", null, 'H', '1', 100, 20, 40, 60);
        fix.exec("A1", null, 'F', '2', 100, 60, 100, 0);
        assertTrue(scan().isEmpty());
    }

    @Test
    void fractionalQuantitiesCompareWithTolerance() {
        fix.out("D", 11, "FX1", 55, "EUR/USD", 54, 1, 38, "0.3", 40, 1);
        fix.in("8", 37, "O1", 11, "FX1", 17, "F1", 150, "F", 39, 1, 38, "0.3", 32, "0.1", 31, "1.1", 151, "0.2", 14, "0.1", 6, "1.1");
        fix.in("8", 37, "O1", 11, "FX1", 17, "F2", 150, "F", 39, 2, 38, "0.3", 32, "0.2", 31, "1.1", 151, "0", 14, "0.3", 6, "1.1");
        assertTrue(scan().isEmpty());
    }
}
