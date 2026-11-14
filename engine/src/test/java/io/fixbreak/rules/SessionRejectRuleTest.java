package io.fixbreak.rules;

import io.fixbreak.parser.FixMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionRejectRuleTest extends RuleTest {
    @Test
    void linksRejectToTheOrderItRefused() {
        fix.logon();
        FixMessage nos = fix.out("D", 11, "A1", 55, "ACME", 54, "Z", 38, 100, 40, 2, 44, "10.00");
        fix.in("3", 45, nos.getLong(34, -1), 371, 54, 372, "D", 373, 5, 58, "Value is incorrect (out of range) for this tag");
        fix.after(60_000).out("0");
        fix.in("0");
        Finding f = single("SESSION_REJECT");
        assertEquals(Severity.HIGH, f.severity);
        assertEquals(List.of("A1"), f.clOrdIds);
        assertTrue(f.summary.contains("NewOrderSingle"), f.summary);
        assertTrue(f.summary.contains("SessionRejectReason(373)=5 (Value is incorrect"), f.summary);
        assertTrue(f.summary.contains("RefTagID(371)=54 (Side)"), f.summary);
        assertEquals(2, f.evidence.length);
        // the order was answered (with a reject), so it is not also a NO_ACK
        assertRules("SESSION_REJECT");
    }

    @Test
    void rejectOfANonOrderMessageHasNoOrder() {
        fix.logon();
        fix.out("0");
        fix.in("3", 45, 2, 373, 10, 58, "SendingTime accuracy problem");
        Finding f = single("SESSION_REJECT");
        assertTrue(f.clOrdIds.isEmpty());
        assertNull(f.rootClOrdId());
    }
}
