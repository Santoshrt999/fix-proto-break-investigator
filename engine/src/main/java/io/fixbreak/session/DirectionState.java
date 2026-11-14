package io.fixbreak.session;

import io.fixbreak.util.Times;

import java.util.ArrayList;
import java.util.List;

/** One direction (sender to target) of a FIX session. Each direction has its own sequence number stream. */
public final class DirectionState {
    /** A silence longer than the heartbeat tolerance while the session was logged on. */
    public record HeartbeatGap(long fromTime, long toTime, long afterRef, long resumeRef) {
        public long seconds() {
            return (toTime - fromTime) / 1000;
        }
    }

    /** A TestRequest that never got a Heartbeat echoing its TestReqID. */
    public record TestRequest(String id, long ref, long time) {}

    /** ResendRequest(2) or SequenceReset(4) sent in this direction. */
    public record SeqEvent(String kind, long ref, long time, long a, long b) {}

    static final int MAX_KEPT = 50;

    public final SessionState session;
    public final int id;
    public final String sender;
    public final String target;

    public long messages;
    public long firstSeq = -1;
    public long lastSeq = -1;
    /** Next MsgSeqNum we expect, -1 until the first message. */
    public long expectedSeq = -1;
    public long lastTime = Times.UNKNOWN;
    public long lastRef;

    public long heartbeats;
    public long testRequests;
    public long possDups;
    public long resendRequests;
    public long gapFills;
    public long hardResets;
    public long heartbeatGapCount;

    public final List<SeqGap> gaps = new ArrayList<>();
    public final List<HeartbeatGap> heartbeatGaps = new ArrayList<>();
    public final List<TestRequest> openTestRequests = new ArrayList<>();
    public final List<SeqEvent> seqEvents = new ArrayList<>();

    DirectionState(SessionState session, int id, String sender, String target) {
        this.session = session;
        this.id = id;
        this.sender = sender;
        this.target = target;
    }

    public String label() {
        return sender + "->" + target;
    }

    public DirectionState opposite() {
        return this == session.forward ? session.reverse : session.forward;
    }

    public long unrecoveredGaps() {
        return gaps.stream().filter(g -> !g.recovered()).count();
    }
}
