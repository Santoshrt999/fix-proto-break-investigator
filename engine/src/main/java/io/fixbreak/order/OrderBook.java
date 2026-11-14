package io.fixbreak.order;

import io.fixbreak.order.OrderState.PendingRequest;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.Tags;
import io.fixbreak.session.DirectionState;
import io.fixbreak.session.SessionState;
import io.fixbreak.session.SessionTracker;
import io.fixbreak.util.LongLongMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The order state machine. Correlates NewOrderSingle, cancel and cancel/replace requests,
 * ExecutionReports, OrderCancelRejects and session/business rejects into one
 * {@link OrderState} per order chain, and describes each step in an {@link OrderUpdate}.
 *
 * <p>It only records facts. Deciding which facts are breaks is the job of the rules.
 */
public final class OrderBook {

    private static final class SessionOrders {
        final Map<String, OrderState> byClOrdId = new HashMap<>();
        final LongLongMap execIds = new LongLongMap();
    }

    /** Recent requests per direction, keyed by MsgSeqNum, so a reject's RefSeqNum(45) can be resolved. */
    private static final class Ring {
        static final int SIZE = 2048;
        final long[] seq = new long[SIZE];
        final long[] ref = new long[SIZE];
        final char[] type = new char[SIZE];
        final OrderState[] order = new OrderState[SIZE];

        void put(long s, OrderState o, long r, char t) {
            int i = (int) (s & (SIZE - 1));
            seq[i] = s;
            order[i] = o;
            ref[i] = r;
            type[i] = t;
        }

        int find(long s) {
            int i = (int) (s & (SIZE - 1));
            return order[i] != null && seq[i] == s ? i : -1;
        }
    }

    private final List<SessionOrders> bySession = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<OrderState> all = new ArrayList<>();
    private final OrderUpdate upd = new OrderUpdate();

    /** Every order chain seen, in order of first appearance. */
    public List<OrderState> orders() {
        return all;
    }

    /** Orders whose chain contains this ClOrdID, or whose OrderID(37) equals it. */
    public List<OrderState> find(String id) {
        List<OrderState> out = new ArrayList<>();
        for (SessionOrders so : bySession) {
            if (so == null) continue;
            OrderState o = so.byClOrdId.get(id);
            if (o != null && !out.contains(o)) out.add(o);
        }
        if (out.isEmpty()) {
            for (OrderState o : all) {
                if (id.equals(o.orderId)) out.add(o);
            }
        }
        return out;
    }

    private SessionOrders orders(SessionState s) {
        while (bySession.size() <= s.id) bySession.add(null);
        SessionOrders so = bySession.get(s.id);
        if (so == null) bySession.set(s.id, so = new SessionOrders());
        return so;
    }

    private Ring ring(DirectionState d, boolean create) {
        while (rings.size() <= d.id) rings.add(null);
        Ring r = rings.get(d.id);
        if (r == null && create) rings.set(d.id, r = new Ring());
        return r;
    }

    private OrderState create(SessionOrders so, SessionState session, String rootId) {
        OrderState o = new OrderState(session, rootId);
        so.byClOrdId.put(rootId, o);
        all.add(o);
        return o;
    }

    /**
     * Applies one message. Returns null when the message is not order-related, or is a
     * PossDup/PossResend repeat of an execution that was already applied.
     */
    public OrderUpdate onMessage(FixMessage m, SessionTracker.Update su) {
        String type = m.msgType();
        if (type == null || type.length() != 1) return null;
        char t = type.charAt(0);
        DirectionState dir = su.direction;
        return switch (t) {
            case 'D' -> newOrder(m, dir);
            case 'G', 'F' -> changeRequest(m, dir, t);
            case '8' -> executionReport(m, dir, su.possDup);
            case '9' -> cancelReject(m, dir);
            case '3', 'j' -> reject(m, dir, t);
            default -> null;
        };
    }

    private OrderUpdate newOrder(FixMessage m, DirectionState dir) {
        String id = m.get(Tags.CL_ORD_ID);
        if (id == null) return null;
        upd.reset('D');
        upd.clOrdId = id;
        SessionOrders so = orders(dir.session);
        OrderState o = so.byClOrdId.get(id);
        if (o == null) o = create(so, dir.session, id);
        else if (o.seenNew()) upd.duplicateClOrdId = true;
        if (!o.seenNew()) {
            o.newRef = m.ref();
            o.newTime = m.time();
            o.symbol = m.get(Tags.SYMBOL);
            o.side = m.getChar(Tags.SIDE);
            o.ordType = m.getChar(Tags.ORD_TYPE);
            o.orderQty = m.getDouble(Tags.ORDER_QTY);
            o.price = m.getDouble(Tags.PRICE);
        }
        touch(o, m);
        ring(dir, true).put(m.getLong(Tags.MSG_SEQ_NUM, -1), o, m.ref(), 'D');
        upd.order = o;
        return upd;
    }

    private OrderUpdate changeRequest(FixMessage m, DirectionState dir, char type) {
        String id = m.get(Tags.CL_ORD_ID);
        if (id == null) return null;
        String orig = m.get(Tags.ORIG_CL_ORD_ID);
        upd.reset(type);
        upd.clOrdId = id;
        upd.origClOrdId = orig;
        SessionOrders so = orders(dir.session);
        OrderState o = orig == null ? null : so.byClOrdId.get(orig);
        if (o == null) o = so.byClOrdId.get(id);
        if (o == null) {
            if (orig != null) {
                o = create(so, dir.session, orig);
                o.placeholder = true;
                upd.unknownOrigClOrdId = orig;
            } else {
                o = create(so, dir.session, id);
            }
        }
        so.byClOrdId.put(id, o);
        o.addId(id);
        if (o.symbol == null) o.symbol = m.get(Tags.SYMBOL);
        if (o.side == 0) o.side = m.getChar(Tags.SIDE);
        o.addPending(new PendingRequest(id, type, m.ref(), m.time(), m.getDouble(Tags.ORDER_QTY), m.getDouble(Tags.PRICE)));
        touch(o, m);
        ring(dir, true).put(m.getLong(Tags.MSG_SEQ_NUM, -1), o, m.ref(), type);
        upd.order = o;
        return upd;
    }

    private OrderUpdate executionReport(FixMessage m, DirectionState dir, boolean possDup) {
        String id = m.get(Tags.CL_ORD_ID);
        String orig = m.get(Tags.ORIG_CL_ORD_ID);
        if (id == null && orig == null) return null;
        upd.reset('8');
        upd.clOrdId = id;
        upd.origClOrdId = orig;
        SessionOrders so = orders(dir.session);

        OrderState o = id == null ? null : so.byClOrdId.get(id);
        OrderState byOrig = orig == null ? null : so.byClOrdId.get(orig);
        if (o == null) o = byOrig;
        if (orig != null && byOrig == null) {
            upd.unknownOrigClOrdId = orig;
            if (o == null) {
                o = create(so, dir.session, orig);
                o.placeholder = true;
            } else {
                so.byClOrdId.put(orig, o); // remember it so the same unknown id is reported once
            }
        }
        if (o == null) o = create(so, dir.session, id); // log starts mid-order: no NewOrderSingle seen
        if (id != null && !o.hasClOrdId(id)) {
            so.byClOrdId.put(id, o);
            o.addId(id);
        }
        upd.order = o;

        String execId = m.get(Tags.EXEC_ID);
        if (execId != null && !execId.isEmpty() && !execId.equals("0")) {
            long earlier = so.execIds.putIfAbsent(LongLongMap.hash(execId), m.ref());
            if (earlier != 0) {
                if (possDup || m.is(Tags.POSS_RESEND, 'Y')) return null; // legitimate resend, already applied
                upd.duplicateExecRef = earlier;
                upd.applied = false;
                touch(o, m);
                return upd;
            }
        }

        char execType = m.getChar(Tags.EXEC_TYPE);
        char ordStatus = m.getChar(Tags.ORD_STATUS);
        char transType = m.getChar(Tags.EXEC_TRANS_TYPE); // FIX 4.2 only
        double lastQty = m.getDouble(Tags.LAST_QTY);
        double cum = m.getDouble(Tags.CUM_QTY);
        double leaves = m.getDouble(Tags.LEAVES_QTY);
        double qty = m.getDouble(Tags.ORDER_QTY);
        double px = m.getDouble(Tags.PRICE);

        boolean bust = execType == 'H' || transType == '1';
        boolean correct = execType == 'G' || transType == '2';
        boolean statusOnly = execType == 'I' || transType == '3';
        boolean trade = !bust && !correct && !statusOnly
                && (execType == 'F'
                || ((execType == '1' || execType == '2') && !(lastQty <= 0))
                || (execType == 0 && lastQty > 0));

        upd.execType = execType;
        upd.ordStatus = ordStatus;
        upd.trade = trade;
        upd.tradeCancel = bust;
        upd.tradeCorrect = correct;
        upd.lastQty = lastQty;
        upd.cumQty = cum;
        upd.leavesQty = leaves;
        upd.prevStatus = o.status;
        upd.prevCumQty = o.cumQty;
        upd.wasTerminal = o.terminal();
        upd.firstExec = o.firstExecTime == io.fixbreak.util.Times.UNKNOWN;
        if (upd.firstExec) o.firstExecTime = m.time();

        if (trade && lastQty > 0) o.sumLastQty += lastQty;
        else if (bust && lastQty > 0) o.sumLastQty -= lastQty;
        else if (correct && !Double.isNaN(cum)) o.sumLastQty = cum; // a correction restates the running total

        if (!Double.isNaN(cum)) o.cumQty = cum;
        if (!Double.isNaN(leaves)) o.leavesQty = leaves;
        double avgPx = m.getDouble(Tags.AVG_PX);
        if (!Double.isNaN(avgPx)) o.avgPx = avgPx;
        String orderId = m.get(Tags.ORDER_ID);
        if (orderId != null && !orderId.equals("NONE")) o.orderId = orderId;
        if (o.symbol == null) o.symbol = m.get(Tags.SYMBOL);
        if (o.side == 0) o.side = m.getChar(Tags.SIDE);

        boolean replaced = execType == '5' || ordStatus == '5';
        if (replaced) {
            PendingRequest p = o.removePending(id);
            if (!Double.isNaN(qty)) o.orderQty = qty;
            else if (p != null && !Double.isNaN(p.qty)) o.orderQty = p.qty;
            if (!Double.isNaN(px)) o.price = px;
            else if (p != null && !Double.isNaN(p.price)) o.price = p.price;
            if (id != null) o.currentClOrdId = id;
        } else if (!Double.isNaN(qty) && (Double.isNaN(o.orderQty) || execType == '0' || execType == 'D')) {
            o.orderQty = qty;
        }
        upd.orderQty = !Double.isNaN(qty) ? qty : o.orderQty;

        // Terminal states are sticky: a stray report after Filled/Canceled must not revive the
        // order (that is what INVALID_STATE_TRANSITION reports). Busts, corrections and
        // restatements are the legitimate ways out of a terminal state.
        if (ordStatus != 0 && (!upd.wasTerminal || bust || correct || execType == 'D')) o.status = ordStatus;
        if (o.terminal()) o.clearPending();

        touch(o, m);
        return upd;
    }

    private OrderUpdate cancelReject(FixMessage m, DirectionState dir) {
        String id = m.get(Tags.CL_ORD_ID);
        String orig = m.get(Tags.ORIG_CL_ORD_ID);
        if (id == null && orig == null) return null;
        upd.reset('9');
        upd.clOrdId = id;
        upd.origClOrdId = orig;
        SessionOrders so = orders(dir.session);
        OrderState o = id == null ? null : so.byClOrdId.get(id);
        if (o == null && orig != null) o = so.byClOrdId.get(orig);
        if (o == null) {
            o = create(so, dir.session, orig != null ? orig : id);
            o.placeholder = true;
            if (id != null) {
                so.byClOrdId.put(id, o);
                o.addId(id);
            }
        }
        PendingRequest p = o.removePending(id);
        if (p != null) {
            upd.referencedRef = p.ref;
            upd.referencedMsgType = p.type;
        }
        String orderId = m.get(Tags.ORDER_ID);
        if (o.orderId == null && orderId != null && !orderId.equals("NONE")) o.orderId = orderId;
        touch(o, m);
        upd.order = o;
        return upd;
    }

    private OrderUpdate reject(FixMessage m, DirectionState dir, char type) {
        upd.reset(type);
        OrderState o = null;
        Ring ring = ring(dir.opposite(), false);
        long refSeq = m.getLong(Tags.REF_SEQ_NUM, -1);
        int slot = ring == null || refSeq < 0 ? -1 : ring.find(refSeq);
        if (slot >= 0) {
            o = ring.order[slot];
            upd.referencedRef = ring.ref[slot];
            upd.referencedMsgType = ring.type[slot];
        } else if (type == 'j') {
            String refId = m.get(Tags.BUSINESS_REJECT_REF_ID);
            if (refId != null) o = orders(dir.session).byClOrdId.get(refId);
        }
        if (o != null) {
            if (upd.referencedMsgType == 'D' || (upd.referencedMsgType == 0 && o.firstExecTime == io.fixbreak.util.Times.UNKNOWN)) {
                o.rejectedBeforeAck = true;
            } else {
                for (PendingRequest p : o.pending()) {
                    if (p.ref == upd.referencedRef) {
                        o.removePending(p.clOrdId);
                        break;
                    }
                }
            }
            touch(o, m);
            upd.clOrdId = o.currentClOrdId;
        }
        upd.order = o;
        return upd;
    }

    private static void touch(OrderState o, FixMessage m) {
        o.addRef(m.ref());
        long t = m.time();
        if (t != io.fixbreak.util.Times.UNKNOWN) o.lastTime = t;
    }
}
