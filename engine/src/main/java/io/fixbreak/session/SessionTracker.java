package io.fixbreak.session;

import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.Tags;
import io.fixbreak.session.DirectionState.HeartbeatGap;
import io.fixbreak.session.DirectionState.SeqEvent;
import io.fixbreak.session.DirectionState.TestRequest;
import io.fixbreak.util.Times;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows every session in the log: sequence numbers per direction, gaps and their recovery
 * (ResendRequest, PossDup resends, SequenceReset with and without GapFill), Logon/Logout and
 * heartbeat silences.
 */
public final class SessionTracker {

    /** What the tracker learned from one message. The instance is reused between calls. */
    public static final class Update {
        public DirectionState direction;
        /** PossDupFlag(43)=Y on the message. */
        public boolean possDup;
        /** A PossDup resend of a sequence number that was already seen; downstream state must not apply it twice. */
        public boolean duplicateResend;
        /** A PossDup resend that filled a recorded gap: it arrives after later messages and is older than them. */
        public boolean gapResend;
        /** A gap revealed by this message, if any. */
        public SeqGap newGap;
    }

    /** A direction is flagged silent after this many heartbeat intervals without any message. */
    public static final double HEARTBEAT_TOLERANCE = 2.0;
    private static final int MAX_GAPS_KEPT = 5000;
    private static final int RECENT_GAPS = 64;

    private final Map<String, Map<String, DirectionState>> index = new HashMap<>();
    private final List<SessionState> sessions = new ArrayList<>();
    private final Update upd = new Update();
    private long gapsNotKept;

    public List<SessionState> sessions() {
        return sessions;
    }

    /** Gaps beyond the per-direction storage cap (only seen in badly damaged logs). */
    public long gapsNotKept() {
        return gapsNotKept;
    }

    public SessionState find(String query) {
        for (SessionState s : sessions) {
            if (s.matches(query)) return s;
        }
        return null;
    }

    private DirectionState direction(String sender, String target) {
        Map<String, DirectionState> bySender = index.get(sender);
        if (bySender != null) {
            DirectionState d = bySender.get(target);
            if (d != null) return d;
        }
        SessionState s = new SessionState(sessions.size(), sender, target);
        sessions.add(s);
        index.computeIfAbsent(sender, k -> new HashMap<>()).put(target, s.forward);
        index.computeIfAbsent(target, k -> new HashMap<>()).put(sender, s.reverse);
        return s.forward;
    }

    /** Returns null when the message has no SenderCompID/TargetCompID (it cannot be attributed to a session). */
    public Update onMessage(FixMessage m) {
        String sender = m.get(Tags.SENDER_COMP_ID);
        String target = m.get(Tags.TARGET_COMP_ID);
        if (sender == null || target == null) return null;

        DirectionState dir = direction(sender, target);
        SessionState sess = dir.session;
        long time = m.time();
        long ref = m.ref();
        String type = m.msgType();
        if (type == null) type = "";

        upd.direction = dir;
        upd.possDup = m.is(Tags.POSS_DUP_FLAG, 'Y');
        upd.duplicateResend = false;
        upd.gapResend = false;
        upd.newGap = null;

        sess.messages++;
        dir.messages++;
        if (sess.beginString == null) sess.beginString = m.get(Tags.BEGIN_STRING);
        if (time != Times.UNKNOWN) {
            if (sess.firstTime == Times.UNKNOWN) sess.firstTime = time;
            sess.lastTime = time;
        }

        boolean logon = type.equals("A");
        if (!logon && sess.loggedOn && sess.heartBtInt > 0 && time != Times.UNKNOWN && dir.lastTime != Times.UNKNOWN
                && time - dir.lastTime > (long) (sess.heartBtInt * 1000L * HEARTBEAT_TOLERANCE)) {
            dir.heartbeatGapCount++;
            if (dir.heartbeatGaps.size() < DirectionState.MAX_KEPT) {
                dir.heartbeatGaps.add(new HeartbeatGap(dir.lastTime, time, dir.lastRef, ref));
            }
        }

        long seq = m.getLong(Tags.MSG_SEQ_NUM, -1);
        if (seq >= 0) trackSequence(m, dir, type, seq, ref, time);

        switch (type) {
            case "A" -> {
                sess.logons++;
                int hb = (int) m.getLong(Tags.HEART_BT_INT, 0);
                if (hb > 0) sess.heartBtInt = hb;
                if (!sess.loggedOn) dir.opposite().lastTime = time; // silence before logon is not a missed heartbeat
                sess.loggedOn = true;
                lifecycle(sess, "Logon", sender, ref, time, null);
            }
            case "5" -> {
                sess.logouts++;
                sess.loggedOn = false;
                lifecycle(sess, "Logout", sender, ref, time, m.get(Tags.TEXT));
            }
            case "0" -> {
                dir.heartbeats++;
                String id = m.get(Tags.TEST_REQ_ID);
                if (id != null) dir.opposite().openTestRequests.removeIf(t -> t.id().equals(id));
            }
            case "1" -> {
                dir.testRequests++;
                String id = m.get(Tags.TEST_REQ_ID);
                if (id != null && dir.openTestRequests.size() < DirectionState.MAX_KEPT) {
                    dir.openTestRequests.add(new TestRequest(id, ref, time));
                }
            }
            case "2" -> {
                dir.resendRequests++;
                long begin = m.getLong(Tags.BEGIN_SEQ_NO, 0);
                long end = m.getLong(Tags.END_SEQ_NO, 0);
                if (end == 0) end = Long.MAX_VALUE; // 0 means "everything after BeginSeqNo"
                seqEvent(dir, "ResendRequest", ref, time, begin, end == Long.MAX_VALUE ? 0 : end);
                List<SeqGap> gaps = dir.opposite().gaps;
                for (int i = Math.max(0, gaps.size() - RECENT_GAPS); i < gaps.size(); i++) {
                    SeqGap g = gaps.get(i);
                    if (!g.tooLow && g.resendRequestRef == 0 && !g.recovered() && begin <= g.to && end >= g.from) {
                        g.resendRequestRef = ref;
                    }
                }
            }
            default -> { }
        }

        dir.lastTime = time != Times.UNKNOWN ? time : dir.lastTime;
        dir.lastRef = ref;
        return upd;
    }

    private void trackSequence(FixMessage m, DirectionState dir, String type, long seq, long ref, long time) {
        boolean logon = type.equals("A");
        boolean seqReset = type.equals("4");
        boolean gapFill = seqReset && m.is(Tags.GAP_FILL_FLAG, 'Y');
        long newSeq = seqReset ? m.getLong(Tags.NEW_SEQ_NO, -1) : -1;
        if (dir.firstSeq < 0) dir.firstSeq = seq;

        if (seqReset && !gapFill) {
            // Reset mode: MsgSeqNum is ignored and the stream simply continues from NewSeqNo.
            dir.hardResets++;
            seqEvent(dir, "SequenceReset-Reset", ref, time, seq, newSeq);
            if (newSeq > 0 && dir.expectedSeq > 0) {
                if (newSeq > dir.expectedSeq) {
                    // Numbers skipped by a hard reset are declared lost: an unrecoverable gap.
                    SeqGap g = addGap(new SeqGap(dir, dir.expectedSeq, newSeq - 1, false, dir.lastRef, ref, time));
                    g.hardReset = true;
                    upd.newGap = g;
                } else if (newSeq < dir.expectedSeq) {
                    // The FIX spec forbids a NewSeqNo lower than the next expected number.
                    SeqGap g = addGap(new SeqGap(dir, newSeq, dir.expectedSeq, true, dir.lastRef, ref, time));
                    g.hardReset = true;
                    upd.newGap = g;
                }
            }
            if (newSeq > 0) dir.expectedSeq = newSeq;
            dir.lastSeq = seq;
            return;
        }
        if (gapFill) {
            dir.gapFills++;
            seqEvent(dir, "SequenceReset-GapFill", ref, time, seq, newSeq);
        }
        long next = gapFill && newSeq > seq ? newSeq : seq + 1;

        boolean restart = logon && (m.is(Tags.RESET_SEQ_NUM_FLAG, 'Y') || (seq == 1 && dir.expectedSeq > 1));
        if (dir.expectedSeq < 0 || restart) {
            if (restart) dir.session.epoch++;
            dir.expectedSeq = next;
        } else if (seq == dir.expectedSeq) {
            dir.expectedSeq = next;
        } else if (seq > dir.expectedSeq) {
            upd.newGap = addGap(new SeqGap(dir, dir.expectedSeq, seq - 1, false, dir.lastRef, ref, time));
            dir.expectedSeq = next;
        } else if (upd.possDup || gapFill) {
            // A resend. Either it fills a hole we recorded, or it repeats something already seen.
            if (upd.possDup) dir.possDups++;
            long coversTo = gapFill ? next - 1 : seq;
            boolean filled = false;
            for (int i = Math.max(0, dir.gaps.size() - RECENT_GAPS); i < dir.gaps.size(); i++) {
                SeqGap g = dir.gaps.get(i);
                if (g.tooLow || g.from > coversTo || g.to < seq) continue;
                g.cover(coversTo, gapFill, ref);
                filled = true;
            }
            if (!filled && !gapFill) upd.duplicateResend = true;
            if (filled && !gapFill) upd.gapResend = true;
            if (next > dir.expectedSeq && gapFill) dir.expectedSeq = next;
            return;
        } else {
            // Lower than expected without PossDup: the sender's sequence went backwards.
            upd.newGap = addGap(new SeqGap(dir, seq, dir.expectedSeq, true, dir.lastRef, ref, time));
            dir.expectedSeq = next;
        }
        if (upd.possDup) dir.possDups++;
        dir.lastSeq = seq;
    }

    private SeqGap addGap(SeqGap g) {
        if (g.direction.gaps.size() < MAX_GAPS_KEPT) g.direction.gaps.add(g);
        else gapsNotKept++;
        return g;
    }

    private static void seqEvent(DirectionState dir, String kind, long ref, long time, long a, long b) {
        if (dir.seqEvents.size() < DirectionState.MAX_KEPT) dir.seqEvents.add(new SeqEvent(kind, ref, time, a, b));
    }

    private static void lifecycle(SessionState s, String kind, String from, long ref, long time, String text) {
        if (s.lifecycle.size() < DirectionState.MAX_KEPT) s.lifecycle.add(new SessionState.Lifecycle(kind, from, ref, time, text));
    }
}
