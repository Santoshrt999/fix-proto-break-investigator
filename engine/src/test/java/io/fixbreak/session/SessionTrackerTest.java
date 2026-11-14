package io.fixbreak.session;

import io.fixbreak.Fix;
import io.fixbreak.parser.FixMessage;
import org.junit.jupiter.api.Test;

import static io.fixbreak.Fix.BROKER;
import static io.fixbreak.Fix.CLIENT;
import static org.junit.jupiter.api.Assertions.*;

class SessionTrackerTest {
    private final Fix fix = new Fix();
    private final SessionTracker tracker = new SessionTracker();

    private SessionTracker.Update feed(FixMessage m) {
        return tracker.onMessage(m);
    }

    private void logon() {
        fix.logon().forEach(this::feed);
    }

    private SessionState session() {
        assertEquals(1, tracker.sessions().size());
        return tracker.sessions().get(0);
    }

    @Test
    void pairsBothDirectionsIntoOneSession() {
        feed(fix.out("A", 98, 0, 108, 30));
        feed(fix.in("A", 98, 0, 108, 30));
        feed(fix.out("0"));
        SessionState s = session();
        assertEquals("BUYSIDE1-BROKERX", s.name);
        assertTrue(s.matches("BROKERX-BUYSIDE1"));
        assertTrue(s.matches("buyside1-brokerx"));
        assertEquals(2, s.forward.messages);
        assertEquals(1, s.reverse.messages);
        assertEquals(30, s.heartBtInt);
        assertEquals("FIX.4.4", s.beginString);
        assertEquals(0, s.totalGaps());
        assertSame(s, tracker.find("BROKERX-BUYSIDE1"));
        assertNull(tracker.find("NOPE-NOPE"));
    }

    @Test
    void detectsAnUnrecoveredGap() {
        logon();
        feed(fix.in("0"));
        fix.lose(BROKER, CLIENT, 3);
        SessionTracker.Update u = feed(fix.in("0"));
        assertNotNull(u.newGap);
        SeqGap g = session().reverse.gaps.get(0);
        assertEquals(3, g.from);
        assertEquals(5, g.to);
        assertEquals(3, g.missing());
        assertFalse(g.recovered());
        assertEquals(0, g.resendRequestRef);
        // the stream carries on from the message that revealed the gap
        assertNull(feed(fix.in("0")).newGap);
        assertEquals(1, session().unrecoveredGaps());
    }

    @Test
    void gapRecoveredByResendAndGapFill() {
        logon();
        fix.lose(BROKER, CLIENT, 3); // 2,3,4 lost; next is 5
        feed(fix.in("0"));
        FixMessage rr = fix.out("2", 7, 2, 16, 0);
        feed(rr);
        SeqGap g = session().reverse.gaps.get(0);
        assertEquals(rr.ref(), g.resendRequestRef);
        assertFalse(g.recovered());

        // 2 is resent as a real message, 3-4 were admin messages and are gap-filled
        SessionTracker.Update resend = feed(fix.sendSeq(BROKER, CLIENT, 2, "8", 43, "Y", 11, "X"));
        assertFalse(resend.duplicateResend, "a resend that fills a hole is new information");
        assertFalse(g.recovered());
        feed(fix.sendSeq(BROKER, CLIENT, 3, "4", 43, "Y", 123, "Y", 36, 6));
        assertTrue(g.recovered());
        assertEquals(1, g.resentMessages);
        assertEquals(1, g.gapFills);
        assertEquals(0, session().unrecoveredGaps());
        assertEquals(1, session().totalGaps());
        // seq 5 was already seen, 6 is next
        assertNull(feed(fix.in("0")).newGap);
    }

    @Test
    void possDupOfSeenMessageIsADuplicateResend() {
        logon();
        feed(fix.in("8", 11, "X"));
        SessionTracker.Update u = feed(fix.sendSeq(BROKER, CLIENT, 2, "8", 43, "Y", 11, "X"));
        assertTrue(u.duplicateResend);
        assertNull(u.newGap);
        assertEquals(1, session().reverse.possDups);
        assertEquals(0, session().totalGaps());
    }

    @Test
    void unsolicitedGapFillMovesTheSequenceForward() {
        logon();
        feed(fix.sendSeq(BROKER, CLIENT, 2, "4", 123, "Y", 36, 10));
        assertNull(feed(fix.sendSeq(BROKER, CLIENT, 10, "0")).newGap);
        assertEquals(0, session().totalGaps());
        assertEquals(1, session().reverse.gapFills);
    }

    @Test
    void hardSequenceResetIsRecordedAndFollowed() {
        logon();
        feed(fix.sendSeq(BROKER, CLIENT, 2, "4", 36, 50));
        assertNull(feed(fix.sendSeq(BROKER, CLIENT, 50, "0")).newGap);
        assertEquals(1, session().reverse.hardResets);
        assertEquals("SequenceReset-Reset", session().reverse.seqEvents.get(0).kind());
    }

    @Test
    void sequenceGoingBackwardsWithoutPossDupIsFlagged() {
        logon();
        feed(fix.in("0"));
        feed(fix.in("0"));
        SessionTracker.Update u = feed(fix.sendSeq(BROKER, CLIENT, 2, "0"));
        assertNotNull(u.newGap);
        assertTrue(u.newGap.tooLow);
        assertFalse(u.newGap.recovered());
    }

    @Test
    void logonWithResetFlagRestartsNumbering() {
        logon();
        feed(fix.out("0"));
        feed(fix.out("5"));
        feed(fix.sendSeq(CLIENT, BROKER, 1, "A", 98, 0, 108, 30, 141, "Y"));
        assertNull(feed(fix.sendSeq(CLIENT, BROKER, 2, "0")).newGap);
        assertEquals(0, session().totalGaps());
        assertEquals(3, session().logons, "one logon pair plus the re-logon");
    }

    @Test
    void flagsHeartbeatSilenceOnlyWhileLoggedOn() {
        logon();
        fix.after(31_000);
        feed(fix.out("0"));
        feed(fix.in("0"));
        assertEquals(0, session().heartbeatGaps(), "31s with HeartBtInt=30 is within tolerance");

        fix.after(95_000);
        feed(fix.out("0")); // client still talking
        feed(fix.in("0"));  // broker back after 95s of silence... but so was the client here
        assertEquals(2, session().heartbeatGaps());
        assertTrue(session().reverse.heartbeatGaps.get(0).seconds() >= 95);

        feed(fix.out("5"));
        feed(fix.in("5"));
        fix.after(3_600_000);
        logon();
        assertEquals(2, session().heartbeatGaps(), "silence across logout/logon is not a missed heartbeat");
    }

    @Test
    void tracksUnansweredTestRequests() {
        logon();
        feed(fix.out("1", 112, "T1"));
        feed(fix.in("0", 112, "T1"));
        feed(fix.out("1", 112, "T2"));
        assertEquals(1, session().forward.openTestRequests.size());
        assertEquals("T2", session().forward.openTestRequests.get(0).id());
        assertEquals(2, session().forward.testRequests);
    }

    @Test
    void separateSessionsDoNotInterfere() {
        feed(fix.send("A1", "B1", "0"));
        feed(fix.send("A2", "B2", "0"));
        feed(fix.send("B1", "A1", "0"));
        assertEquals(2, tracker.sessions().size());
        assertEquals("A1-B1", tracker.sessions().get(0).name);
        assertEquals(2, tracker.sessions().get(0).messages);
    }

    @Test
    void messageWithoutCompIdsIsIgnored() {
        assertNull(feed(fix.addLine("8=FIX.4.4|9=5|35=0|10=000|")));
        assertTrue(tracker.sessions().isEmpty());
    }
}
