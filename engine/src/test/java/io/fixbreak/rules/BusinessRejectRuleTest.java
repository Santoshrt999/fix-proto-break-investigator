package io.fixbreak.rules;

import io.fixbreak.parser.FixMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRejectRuleTest extends RuleTest {
    @Test
    void reportsBusinessRejectAgainstTheOrder() {
        fix.logon();
        FixMessage nos = fix.newOrder("A1", "ACME", 100, "10.00");
        fix.in("j", 45, nos.getLong(34, -1), 372, "D", 379, "A1", 380, 4, 58, "Order routing unavailable");
        fix.after(60_000).out("0");
        fix.in("0");
        Finding f = single("BUSINESS_REJECT");
        assertEquals(Severity.HIGH, f.severity);
        assertEquals(List.of("A1"), f.clOrdIds);
        assertTrue(f.summary.contains("BusinessRejectReason(380)=4 (Application not available)"), f.summary);
        assertTrue(f.summary.contains("Order routing unavailable"), f.summary);
        assertRules("BUSINESS_REJECT");
    }

    @Test
    void unlinkedBusinessRejectStillReported() {
        fix.in("j", 372, "AE", 379, "TRD-1", 380, 3);
        Finding f = single("BUSINESS_REJECT");
        assertTrue(f.clOrdIds.isEmpty());
        assertTrue(f.summary.contains("TradeCaptureReport"), f.summary);
        assertTrue(f.summary.contains("Unsupported Message Type"), f.summary);
    }
}
