package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderRejectedRuleTest extends RuleTest {
    @Test
    void reportsRejectWithReasonAndMaskedText() {
        fix.newOrder("A1", "ACME", 50000, "250.00");
        fix.exec("A1", null, '8', '8', 50000, 0, 0, 0);
        fix.lines().set(1, fix.lines().get(1)); // keep
        Finding f = single("ORDER_REJECTED");
        assertEquals(Severity.MEDIUM, f.severity);
        assertEquals(List.of("A1"), f.clOrdIds);
        assertEquals("BUYSIDE1-BROKERX", f.session);
        assertEquals(2, f.evidence.length, "NewOrderSingle and the reject");
        assertTrue(f.summary.contains("rejected by BROKERX"), f.summary);
        assertRules("ORDER_REJECTED");
    }

    @Test
    void includesOrdRejReasonMeaningAndMasksAccountInText() {
        fix.newOrder("A1", "ACME", 50000, "250.00");
        fix.in("8", 37, "NONE", 11, "A1", 17, "R1", 150, 8, 39, 8, 55, "ACME", 54, 1, 38, 50000, 151, 0, 14, 0, 6, 0,
                103, 3, 58, "Order exceeds limit for account ACC90210");
        Finding f = single("ORDER_REJECTED");
        assertTrue(f.summary.contains("OrdRejReason(103)=3 (Order exceeds limit)"), f.summary);
        assertTrue(f.summary.contains("for account ***"), f.summary);
        assertFalse(f.summary.contains("ACC90210"), f.summary);
        assertTrue(f.summary.contains("Buy 50000 ACME @ 250"), f.summary);
    }

    @Test
    void cleanOrderIsNotReported() {
        cleanOrder("A1");
        assertTrue(scan().isEmpty());
    }

    @Test
    void repeatedRejectIsReportedOnce() {
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.exec("A1", null, '8', '8', 100, 0, 0, 0);
        fix.exec("A1", null, '8', '8', 100, 0, 0, 0);
        single("ORDER_REJECTED");
    }
}
