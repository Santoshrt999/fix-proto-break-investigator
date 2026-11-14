package io.fixbreak.order;

import io.fixbreak.session.SessionState;
import io.fixbreak.util.Times;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything known about one order, including its whole amend/cancel chain
 * (every ClOrdID linked through OrigClOrdID(41)). Kept deliberately compact:
 * a 1 GB log can hold over a million of these.
 */
public final class OrderState {
    /** A cancel or cancel/replace request that has not been answered yet. */
    public static final class PendingRequest {
        public final String clOrdId;
        /** 'F' cancel or 'G' cancel/replace. */
        public final char type;
        public final long ref;
        public final long time;
        public final double qty;
        public final double price;

        PendingRequest(String clOrdId, char type, long ref, long time, double qty, double price) {
            this.clOrdId = clOrdId;
            this.type = type;
            this.ref = ref;
            this.time = time;
            this.qty = qty;
            this.price = price;
        }
    }

    // Bits for markOnce(). Bits 16 and above are free for contributed rules.
    public static final int MARK_OVERFILL = 1;
    public static final int MARK_QTY_MISMATCH = 1 << 1;
    public static final int MARK_LATE_ACK = 1 << 2;

    public final SessionState session;
    private final String rootClOrdId;
    private String[] laterIds;
    /** The ClOrdID currently live at the counterparty (changes after an accepted replace). */
    public String currentClOrdId;

    public String orderId;
    public String symbol;
    public char side;
    public char ordType;
    public double orderQty = Double.NaN;
    public double price = Double.NaN;

    /** Latest OrdStatus(39); 0 until the first ExecutionReport. */
    public char status;
    public double cumQty;
    public double leavesQty = Double.NaN;
    public double avgPx = Double.NaN;
    /** Sum of LastQty(32) over trade reports, net of trade cancels. Compared with CumQty by QTY_MISMATCH. */
    public double sumLastQty;

    /** The NewOrderSingle line, 0 if the log never showed one. */
    public long newRef;
    public long newTime = Times.UNKNOWN;
    public long firstExecTime = Times.UNKNOWN;
    public long lastTime = Times.UNKNOWN;

    /** True if the NewOrderSingle was refused with a session-level Reject(3) or BusinessMessageReject(j). */
    public boolean rejectedBeforeAck;
    /** SessionState.epoch when this order was created: ClOrdIDs may be reused in a later epoch. */
    public int epoch;
    /** True if this state was created for an OrigClOrdID that the log never introduced. */
    public boolean placeholder;

    private int marks;
    private long[] refs = new long[4];
    private int refCount;
    private List<PendingRequest> pending;

    OrderState(SessionState session, String rootClOrdId) {
        this.session = session;
        this.rootClOrdId = rootClOrdId;
        this.currentClOrdId = rootClOrdId;
    }

    public String rootClOrdId() {
        return rootClOrdId;
    }

    /** Every ClOrdID in the chain, oldest first. */
    public List<String> chain() {
        if (laterIds == null) return List.of(rootClOrdId);
        List<String> out = new ArrayList<>(laterIds.length + 1);
        out.add(rootClOrdId);
        out.addAll(Arrays.asList(laterIds));
        return out;
    }

    public boolean hasClOrdId(String id) {
        if (rootClOrdId.equals(id)) return true;
        if (laterIds != null) {
            for (String s : laterIds) if (s.equals(id)) return true;
        }
        return false;
    }

    void addId(String id) {
        if (hasClOrdId(id)) return;
        laterIds = laterIds == null ? new String[] {id} : append(laterIds, id);
    }

    private static String[] append(String[] a, String s) {
        String[] b = Arrays.copyOf(a, a.length + 1);
        b[a.length] = s;
        return b;
    }

    /** True once a NewOrderSingle was seen for this order. */
    public boolean seenNew() {
        return newRef != 0;
    }

    public boolean terminal() {
        return isTerminal(status);
    }

    /** Filled, Done for day, Canceled, Rejected and Expired end an order's life. */
    public static boolean isTerminal(char ordStatus) {
        return ordStatus == '2' || ordStatus == '3' || ordStatus == '4' || ordStatus == '8' || ordStatus == 'C';
    }

    /** Returns true the first time it is called for {@code bit} on this order; lets a rule report once per order. */
    public boolean markOnce(int bit) {
        if ((marks & bit) != 0) return false;
        marks |= bit;
        return true;
    }

    void addRef(long ref) {
        if (refCount == refs.length) refs = Arrays.copyOf(refs, refCount * 2);
        refs[refCount++] = ref;
    }

    /** Log lines of every message that touched this order, in log order. */
    public long[] refs() {
        return Arrays.copyOf(refs, refCount);
    }

    public int refCount() {
        return refCount;
    }

    public long lastRef() {
        return refCount == 0 ? 0 : refs[refCount - 1];
    }

    public long refFromEnd(int back) {
        return back < refCount ? refs[refCount - 1 - back] : 0;
    }

    public List<PendingRequest> pending() {
        return pending == null ? List.of() : pending;
    }

    void addPending(PendingRequest p) {
        if (pending == null) pending = new ArrayList<>(2);
        pending.add(p);
    }

    PendingRequest removePending(String clOrdId) {
        if (pending == null || clOrdId == null) return null;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).clOrdId.equals(clOrdId)) return pending.remove(i);
        }
        return null;
    }

    void clearPending() {
        pending = null;
    }
}
