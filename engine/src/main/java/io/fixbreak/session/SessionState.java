package io.fixbreak.session;

import io.fixbreak.util.Times;

import java.util.ArrayList;
import java.util.List;

/**
 * A FIX session: an unordered SenderCompID/TargetCompID pair. It is named after the direction
 * of the first message seen (normally the initiator's Logon), e.g. {@code BUYSIDE1-BROKERX}.
 */
public final class SessionState {
    /** Logon or Logout seen on the session. */
    public record Lifecycle(String kind, String from, long ref, long time, String text) {}

    public final int id;
    public final String name;
    public final DirectionState forward;
    public final DirectionState reverse;

    public String beginString;
    public long messages;
    public long firstTime = Times.UNKNOWN;
    public long lastTime = Times.UNKNOWN;
    public boolean loggedOn;
    /** HeartBtInt(108) from the most recent Logon, 0 if unknown. */
    public int heartBtInt;
    public long logons;
    public long logouts;
    /** Incremented whenever sequence numbers restart (Logon with ResetSeqNumFlag, or MsgSeqNum back to 1 on Logon). IDs may be reused across epochs. */
    public int epoch;
    public final List<Lifecycle> lifecycle = new ArrayList<>();

    SessionState(int id, String sender, String target) {
        this.id = id;
        this.name = sender + "-" + target;
        this.forward = new DirectionState(this, id * 2, sender, target);
        this.reverse = new DirectionState(this, id * 2 + 1, target, sender);
    }

    /** True if {@code query} names this session in either direction, e.g. "A-B" or "B-A". */
    public boolean matches(String query) {
        return query.equalsIgnoreCase(name) || query.equalsIgnoreCase(reverse.sender + "-" + reverse.target);
    }

    public long totalGaps() {
        return forward.gaps.size() + reverse.gaps.size();
    }

    public long unrecoveredGaps() {
        return forward.unrecoveredGaps() + reverse.unrecoveredGaps();
    }

    public long heartbeatGaps() {
        return forward.heartbeatGapCount + reverse.heartbeatGapCount;
    }
}
