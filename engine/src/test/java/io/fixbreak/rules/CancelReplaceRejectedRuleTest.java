package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CancelReplaceRejectedRuleTest extends RuleTest {
    @Test
    void cancelTooLate() {
        cleanOrder("A1");
        fix.out("F", 11, "A2", 41, "A1", 55, "ACME", 54, 1);
        fix.in("9", 37, "BX-1", 11, "A2", 41, "A1", 39, 2, 434, 1, 102, 0, 58, "Too late to cancel");
        Finding f = single("CANCEL_REPLACE_REJECTED");
        assertEquals(List.of("A1", "A2"), f.clOrdIds);
        assertTrue(f.summary.startsWith("Cancel request A2 (for A1) rejected by BROKERX"), f.summary);
        assertTrue(f.summary.contains("CxlRejReason(102)=0 (Too late to cancel)"), f.summary);
        assertTrue(f.summary.contains("2 (Filled)"), f.summary);
        assertEquals(3, f.evidence.length, "request, reject, original order");
        assertRules("CANCEL_REPLACE_REJECTED");
    }

    @Test
    void replaceRejected() {
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.ack("A1", 100);
        fix.out("G", 11, "A2", 41, "A1", 55, "ACME", 54, 1, 38, 200, 40, 2, 44, "10.00");
        fix.in("9", 37, "BX-1", 11, "A2", 41, "A1", 39, 0, 434, 2, 102, 2);
        fix.fill("A1", 100, 100, 100);
        Finding f = single("CANCEL_REPLACE_REJECTED");
        assertTrue(f.summary.startsWith("Cancel/replace request A2"), f.summary);
        assertRules("CANCEL_REPLACE_REJECTED");
        assertEquals(100, engine.book.orders().get(0).orderQty, "a rejected replace must not change the order");
    }

    @Test
    void acceptedCancelIsNotReported() {
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.ack("A1", 100);
        fix.out("F", 11, "A2", 41, "A1", 55, "ACME", 54, 1);
        fix.exec("A2", "A1", '4', '4', 100, 0, 0, 0);
        assertTrue(scan().isEmpty());
    }
}
