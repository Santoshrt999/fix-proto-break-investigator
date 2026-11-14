package io.fixbreak.rules;

import io.fixbreak.Fix;
import io.fixbreak.gen.FixBuilder;
import io.fixbreak.util.Times;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Gap recovery, sequence resets and multi-message lines: cases that must not produce false breaks. */
class RecoveryAndResetTest extends RuleTest {

    @Test
    void lateGapFillResendIsNotAFalseBreak() {
        fix.logon();
        fix.newOrder("O1", "ACME", 100, "10.00");
        fix.ack("O1", 100);
        long lost = fix.peekSeq(Fix.BROKER, Fix.CLIENT);
        fix.lose(Fix.BROKER, Fix.CLIENT, 1);                 // the partial fill of 40 goes missing
        fix.execWithId("X3", "O1", null, 'F', '2', 100, 60, 100, 0); // final fill arrives first
        fix.out("2", 7, lost, 16, lost);                      // ResendRequest
        fix.sendSeq(Fix.BROKER, Fix.CLIENT, lost, "8", 43, "Y", 122, Times.fix(fix.now()),
                37, "BX-1", 11, "O1", 17, "X2", 150, 'F', 39, '1', 55, "ACME", 54, 1, 38, 100,
                32, 40, 31, "10.00", 151, 60, 14, 40, 6, "10.00");
        assertRules("SEQ_GAP");
        assertEquals(Severity.LOW, single("SEQ_GAP").severity);
    }

    @Test
    void unrecoveredGapStillReportsTheQuantityMismatchAtEnd() {
        fix.logon();
        fix.newOrder("O1", "ACME", 100, "10.00");
        fix.ack("O1", 100);
        fix.lose(Fix.BROKER, Fix.CLIENT, 1);                 // the fill of 40 is never resent
        fix.execWithId("X3", "O1", null, 'F', '2', 100, 60, 100, 0);
        assertRules("SEQ_GAP", "QTY_MISMATCH");
        assertTrue(single("QTY_MISMATCH").summary.contains("sequence gap was open"), single("QTY_MISMATCH").summary);
    }

    @Test
    void clOrdIdReusedAfterSequenceResetIsANewOrder() {
        fix.logon();
        fix.newOrder("1", "XYZ", 100, "10.00");
        fix.execWithId("E1", "1", null, '0', '0', 100, 0, 0, 100);
        fix.execWithId("E2", "1", null, 'F', '2', 100, 100, 100, 0);

        fix.after(3_600_000).resetSequences();
        fix.out("A", 98, 0, 108, 30, 141, "Y");
        fix.in("A", 98, 0, 108, 30, 141, "Y");
        fix.newOrder("1", "ACME", 50, "20.00");
        fix.execWithId("E1", "1", null, '0', '0', 50, 0, 0, 50);  // venue reuses ExecIDs on the new day
        fix.execWithId("E2", "1", null, 'F', '2', 50, 50, 50, 0);

        assertTrue(scan().isEmpty(), () -> scan().toString());
        assertEquals(2, engine.book.find("1").size());
    }

    @Test
    void rejectAfterResetIsNotTiedToAnOrderFromBeforeIt() {
        fix.logon();
        fix.newOrder("OLD1", "ACME", 100, "10.00");          // client seq 2
        fix.ack("OLD1", 100);
        fix.after(3_600_000).resetSequences();
        fix.out("A", 98, 0, 108, 30, 141, "Y");
        fix.in("A", 98, 0, 108, 30, 141, "Y");
        fix.out("0");                                        // client seq 2 again: a heartbeat
        fix.in("3", 45, 2, 373, 1, 58, "bad heartbeat");     // no RefMsgType(372)
        Finding f = single("SESSION_REJECT");
        assertTrue(f.clOrdIds == null || f.clOrdIds.isEmpty(), f.summary);
        assertFalse(f.summary.contains("OLD1"), f.summary);
    }

    @Test
    void refMsgTypeMismatchUnlinksTheReject() {
        fix.logon();
        fix.newOrder("O1", "ACME", 100, "10.00");            // client seq 2
        fix.in("3", 45, 2, 372, "0", 373, 1, 58, "refers to a heartbeat");
        assertFalse(single("SESSION_REJECT").summary.contains("O1"));
    }

    @Test
    void sequenceResetWithoutGapFillReportsSkippedAndBackwardsNumbers() {
        fix.logon();
        long next = fix.peekSeq(Fix.BROKER, Fix.CLIENT);
        fix.in("4", 36, 50);                                 // hard reset jumps to 50
        fix.lose(Fix.BROKER, Fix.CLIENT, (int) (50 - next - 1));
        fix.in("0");                                         // seq 50
        fix.in("4", 36, 10);                                 // hard reset backwards: not allowed
        assertRules("SEQ_GAP");
        var gaps = of("SEQ_GAP");
        assertEquals(2, gaps.size());
        assertTrue(gaps.get(0).summary.contains("declared those messages lost"), gaps.get(0).summary);
        assertEquals(Severity.HIGH, gaps.get(0).severity);
        assertTrue(gaps.get(1).summary.contains("below the expected"), gaps.get(1).summary);
    }

    @Test
    void twoMessagesOnOneLineAreBothRead() {
        String a = new FixBuilder("FIX.4.4", "0").add(49, "A").add(56, "B").add(34, 1).add(52, "20260105-09:30:00").build("|");
        String b = new FixBuilder("FIX.4.4", "0").add(49, "A").add(56, "B").add(34, 2).add(52, "20260105-09:30:01").build("|");
        fix.addLine(a + b);
        assertTrue(scan().isEmpty());
        assertEquals(2, engine.messages);
    }

    @Test
    void aLogWithoutLineBreaksIsReadMessageByMessage() {
        StringBuilder stream = new StringBuilder();
        for (int i = 1; i <= 3000; i++) {
            stream.append(new FixBuilder("FIX.4.4", "0").add(49, "A").add(56, "B").add(34, i)
                    .add(52, "20260105-09:30:00").build("\u0001"));
        }
        fix.addLine(stream.toString());                      // one physical "line" of ~200 KB
        assertTrue(scan().isEmpty(), () -> scan().toString());
        assertEquals(3000, engine.messages);
    }
}
