package io.fixbreak.session;

/** A hole in one direction's MsgSeqNum stream, and what (if anything) was done to recover it. */
public final class SeqGap {
    public final DirectionState direction;
    /** First and last missing sequence numbers (inclusive). For {@link #tooLow}, {@code from} is the number received and {@code to} the number expected. */
    public final long from;
    public final long to;
    /** True when the sequence number went backwards without PossDupFlag, rather than skipping ahead. */
    public final boolean tooLow;
    /** Last message seen before the gap (0 if none) and the message that revealed it. */
    public final long beforeRef;
    public final long detectRef;
    public final long detectTime;

    /** The ResendRequest from the other side that asked for these numbers, 0 if none was seen. */
    public long resendRequestRef;
    /** Highest missing sequence number later covered by a PossDup resend or a GapFill. */
    public long coveredTo;
    public int resentMessages;
    public int gapFills;
    public long firstRecoveryRef;
    public long lastRecoveryRef;

    SeqGap(DirectionState direction, long from, long to, boolean tooLow, long beforeRef, long detectRef, long detectTime) {
        this.direction = direction;
        this.from = from;
        this.to = to;
        this.tooLow = tooLow;
        this.beforeRef = beforeRef;
        this.detectRef = detectRef;
        this.detectTime = detectTime;
        this.coveredTo = from - 1;
    }

    public long missing() {
        return to - from + 1;
    }

    /** True once resends and gap fills have covered every missing number. */
    public boolean recovered() {
        return !tooLow && coveredTo >= to;
    }

    boolean contains(long seq) {
        return !tooLow && seq >= from && seq <= to;
    }

    void cover(long upTo, boolean gapFill, long ref) {
        if (upTo > coveredTo) coveredTo = Math.min(upTo, to);
        if (gapFill) gapFills++;
        else resentMessages++;
        if (firstRecoveryRef == 0) firstRecoveryRef = ref;
        lastRecoveryRef = ref;
    }
}
