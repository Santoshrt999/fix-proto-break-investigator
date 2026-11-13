package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoAckRuleTest extends RuleTest {
    @Test
    void orderNeverAcknowledged() {
        fix.logon();
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.after(45_000).out("0");
        fix.in("0");
        Finding f = single("NO_ACK");
        assertEquals(Severity.HIGH, f.severity);
        assertEquals("A1", f.rootClOrdId());
        assertTrue(f.summary.contains("never got an ExecutionReport"), f.summary);
        assertEquals(1, f.evidence.length);
    }

    @Test
    void notFlaggedWhenLogEndsBeforeTheTimeout() {
        fix.logon();
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.after(10_000).out("0");
        assertNone("NO_ACK");
    }

    @Test
    void lateAckIsMediumSeverity() {
        fix.logon();
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.after(45_000);
        fix.ack("A1", 100);
        fix.fill("A1", 100, 100, 100);
        Finding f = single("NO_ACK");
        assertEquals(Severity.MEDIUM, f.severity);
        assertTrue(f.summary.contains("first ExecutionReport after 45s"), f.summary);
    }

    @Test
    void timeoutIsConfigurable() {
        config.ackTimeoutMillis = 2_000;
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.after(3_000);
        fix.ack("A1", 100);
        fix.fill("A1", 100, 100, 100);
        single("NO_ACK");
    }

    @Test
    void promptAckIsFine() {
        cleanOrder("A1");
        fix.after(120_000).out("0");
        assertNone("NO_ACK");
    }
}
