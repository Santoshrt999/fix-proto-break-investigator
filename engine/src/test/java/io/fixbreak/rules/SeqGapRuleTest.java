package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import static io.fixbreak.Fix.BROKER;
import static io.fixbreak.Fix.CLIENT;
import static org.junit.jupiter.api.Assertions.*;

class SeqGapRuleTest extends RuleTest {
    @Test
    void unrecoveredGapIsHigh() {
        fix.logon();
        fix.lose(BROKER, CLIENT, 2);
        fix.in("0");
        fix.out("2", 7, 2, 16, 0);
        fix.in("0");
        Finding f = single("SEQ_GAP");
        assertEquals(Severity.HIGH, f.severity);
        assertEquals("BUYSIDE1-BROKERX", f.session);
        assertTrue(f.clOrdIds.isEmpty());
        assertTrue(f.summary.contains("BROKERX->BUYSIDE1: expected 2, received 4 (2 messages missing)"), f.summary);
        assertTrue(f.summary.contains("ResendRequest sent"), f.summary);
        assertTrue(f.summary.contains("NOT recovered"), f.summary);
        assertEquals(3, f.evidence.length, "message before, message revealing the gap, ResendRequest");
    }

    @Test
    void recoveredGapIsLow() {
        fix.logon();
        fix.lose(BROKER, CLIENT, 2);
        fix.in("0");
        fix.out("2", 7, 2, 16, 0);
        fix.sendSeq(BROKER, CLIENT, 2, "4", 43, "Y", 123, "Y", 36, 5);
        fix.in("0");
        Finding f = single("SEQ_GAP");
        assertEquals(Severity.LOW, f.severity);
        assertTrue(f.summary.contains("recovered by 1 SequenceReset-GapFill"), f.summary);
    }

    @Test
    void gapWithNoResendRequest() {
        fix.logon();
        fix.lose(CLIENT, BROKER, 1);
        fix.out("0");
        Finding f = single("SEQ_GAP");
        assertTrue(f.summary.contains("BUYSIDE1->BROKERX"), f.summary);
        assertTrue(f.summary.contains("1 message missing"), f.summary);
        assertTrue(f.summary.contains("no ResendRequest seen"), f.summary);
    }

    @Test
    void sequenceGoingBackwards() {
        fix.logon();
        fix.in("0");
        fix.in("0");
        fix.sendSeq(BROKER, CLIENT, 2, "0");
        Finding f = single("SEQ_GAP");
        assertEquals(Severity.HIGH, f.severity);
        assertTrue(f.summary.contains("went backwards"), f.summary);
    }

    @Test
    void continuousSequenceIsClean() {
        fix.logon();
        for (int i = 0; i < 20; i++) {
            fix.out("0");
            fix.in("0");
        }
        assertTrue(scan().isEmpty());
    }
}
