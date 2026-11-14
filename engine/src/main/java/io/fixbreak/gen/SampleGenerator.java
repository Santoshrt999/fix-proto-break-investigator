package io.fixbreak.gen;

import io.fixbreak.util.Times;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Writes a synthetic FIX 4.4 log: one fictional buy-side firm (BUYSIDE1) trading with two
 * fictional brokers (BROKERX, BROKERY). Normal order flow is generated from a fixed random
 * seed, and one instance of every break the engine detects can be planted in it.
 *
 * <p>Everything here is invented. No real firm, account, symbol or log format is used.
 */
public final class SampleGenerator {

    public static final class Options {
        public long seed = 42;
        /** Number of normal (healthy) orders. */
        public int orders = 160;
        /** Plant one instance of every break. */
        public boolean plant = true;
        /** Field delimiter written to the log: "|" (readable), "\u0001" (SOH) or "^A". */
        public String delimiter = "|";
    }

    /** A break the generator put in the log on purpose. {@code clOrdId} is the first ClOrdID of the order's chain, null for session-level breaks. */
    public record Planted(String ruleId, String session, String clOrdId, String note) {}

    private static final String[] SYMBOLS = {"ACME", "GLOBEX", "INITECH", "UMBRELLA", "HOOLI", "STARK", "WAYNE", "SOYLENT"};
    private static final double[] BASE_PX = {48.20, 112.35, 23.10, 310.00, 76.45, 198.60, 54.75, 9.80};
    private static final String[] ACCOUNTS = {"ACC10001", "ACC10002", "ACC10003", "ACC20417", "ACC20998"};
    private static final String[] TRADERS = {"TRDR-KLEE", "TRDR-MOSS", "TRDR-ABEL"};
    private static final int HEARTBEAT_SECONDS = 30;

    private static final int DAMAGE_NONE = 0;
    private static final int DAMAGE_CHECKSUM = 1;
    private static final int DAMAGE_TRUNCATE = 2;

    private record Event(long time, long order, Runnable action) implements Comparable<Event> {
        @Override
        public int compareTo(Event o) {
            int c = Long.compare(time, o.time);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }

    private static final class Sess {
        final String client;
        final String broker;
        final String idPrefix;
        long clientSeq = 1;
        long brokerSeq = 1;
        long lastClient;
        long lastBroker;
        boolean brokerSilent;
        long orderNo;
        long execNo;

        Sess(String client, String broker, String idPrefix) {
            this.client = client;
            this.broker = broker;
            this.idPrefix = idPrefix;
        }

        String name() {
            return client + "-" + broker;
        }
    }

    private static final class Ord {
        final Sess s;
        String clOrdId;
        String rootId;
        String orderId;
        String symbol;
        char side;
        long qty;
        double px;
        long cum;
        String account;
        String trader;

        Ord(Sess s) {
            this.s = s;
        }
    }

    private final Options opt;
    private final Random rnd;
    private final PriorityQueue<Event> queue = new PriorityQueue<>();
    private final List<Planted> planted = new ArrayList<>();
    private OutputStream out;
    private long eventNo;
    private long clock;
    private long idCounter;
    private long lines;

    public SampleGenerator(Options opt) {
        this.opt = opt;
        this.rnd = new Random(opt.seed);
    }

    public long linesWritten() {
        return lines;
    }

    /** Writes the log and returns the breaks planted in it, in log order. */
    public List<Planted> generate(OutputStream target) throws IOException {
        out = new BufferedOutputStream(target, 1 << 16);
        long start = Times.parseLoose("2026-01-05T09:30:00");
        long duration = Math.max(2_700_000L, opt.orders * 250L);
        long end = start + duration;
        clock = start;

        Sess x = new Sess("BUYSIDE1", "BROKERX", "BX");
        Sess y = new Sess("BUYSIDE1", "BROKERY", "BY");
        for (Sess s : List.of(x, y)) {
            long t = start + (s == x ? 0 : 150);
            at(t, () -> emit(s, true, "A", b -> b.add(98, 0).add(108, HEARTBEAT_SECONDS).add(141, "Y")));
            at(t + 40, () -> emit(s, false, "A", b -> b.add(98, 0).add(108, HEARTBEAT_SECONDS).add(141, "Y")));
            heartbeatTick(s, t + 1000, end);
            at(end + (s == x ? 0 : 150), () -> emit(s, true, "5", b -> b.add(58, "End of day")));
            at(end + (s == x ? 60 : 210), () -> emit(s, false, "5", null));
        }

        long outageStart = start + 20 * 60_000L;
        long outageEnd = outageStart + 95_000;
        for (int i = 0; i < opt.orders; i++) {
            Sess s = rnd.nextInt(100) < 72 ? x : y;
            long t0 = start + 5_000 + (long) (rnd.nextDouble() * (duration - 50_000));
            // keep BROKERY orders clear of its outage so the only break there is the outage itself
            if (opt.plant && s == y && t0 > outageStart - 30_000 && t0 < outageEnd + 5_000) t0 = outageEnd + 5_000 + rnd.nextInt(60_000);
            normalOrder(s, t0);
        }
        if (opt.plant) plantBreaks(x, y, start, outageStart, outageEnd);

        try {
            for (Event e; (e = queue.poll()) != null; ) {
                clock = Math.max(clock, e.time());
                e.action().run();
            }
            out.flush();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return planted;
    }

    // ------------------------------------------------------------------ plumbing

    private void at(long time, Runnable action) {
        queue.add(new Event(time, eventNo++, action));
    }

    private long between(int lo, int hi) {
        return lo + rnd.nextInt(hi - lo + 1);
    }

    private void heartbeatTick(Sess s, long t, long end) {
        if (t >= end) return;
        at(t, () -> {
            long idle = HEARTBEAT_SECONDS * 1000L;
            if (clock - s.lastClient >= idle) emit(s, true, "0", null);
            if (!s.brokerSilent && clock - s.lastBroker >= idle) emit(s, false, "0", null);
            heartbeatTick(s, t + 1000, end);
        });
    }

    private long emit(Sess s, boolean fromClient, String type, Consumer<FixBuilder> body) {
        return emit(s, fromClient, type, -1, body, DAMAGE_NONE);
    }

    /** Writes one message. {@code forcedSeq >= 0} sends that MsgSeqNum without advancing the counter (resends, gap fills). */
    private long emit(Sess s, boolean fromClient, String type, long forcedSeq, Consumer<FixBuilder> body, int damage) {
        long seq = forcedSeq >= 0 ? forcedSeq : fromClient ? s.clientSeq++ : s.brokerSeq++;
        FixBuilder b = new FixBuilder("FIX.4.4", type)
                .add(49, fromClient ? s.client : s.broker).add(56, fromClient ? s.broker : s.client)
                .add(34, seq).add(52, Times.fix(clock));
        if (body != null) body.accept(b);
        String msg = b.build();
        if (damage == DAMAGE_CHECKSUM) {
            int i = msg.lastIndexOf("10=");
            int sum = Integer.parseInt(msg.substring(i + 3, i + 6));
            msg = msg.substring(0, i + 3) + String.format("%03d", (sum + 7) % 256) + FixBuilder.SOH;
        } else if (damage == DAMAGE_TRUNCATE) {
            msg = msg.substring(0, msg.indexOf(FixBuilder.SOH + "52=") + 4 + 14); // cut inside SendingTime
        }
        String line = Times.logPrefix(clock) + (fromClient ? " OUT " : " IN  ") + msg.replace(String.valueOf(FixBuilder.SOH), opt.delimiter) + "\n";
        try {
            out.write(line.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lines++;
        if (fromClient) s.lastClient = clock;
        else s.lastBroker = clock;
        return seq;
    }

    private String nextClOrdId() {
        return String.format("ORD-%06d", ++idCounter);
    }

    private static String px(double p) {
        return String.format(Locale.ROOT, "%.2f", p);
    }

    // ------------------------------------------------------------------ order messages

    private Ord order(Sess s, String symbol, char side, long qty, double price) {
        Ord o = new Ord(s);
        o.symbol = symbol;
        o.side = side;
        o.qty = qty;
        o.px = price;
        o.account = ACCOUNTS[rnd.nextInt(ACCOUNTS.length)];
        o.trader = TRADERS[rnd.nextInt(TRADERS.length)];
        return o;
    }

    private Ord randomOrder(Sess s) {
        int i = rnd.nextInt(SYMBOLS.length);
        double price = Math.round((BASE_PX[i] + (rnd.nextInt(41) - 20) / 100.0) * 100) / 100.0;
        return order(s, SYMBOLS[i], rnd.nextBoolean() ? '1' : '2', 100L * (1 + rnd.nextInt(50)), price);
    }

    /** Sends the NewOrderSingle. {@code sideOverride} lets a plant send an invalid Side. Returns its MsgSeqNum. */
    private long nos(Ord o, String sideOverride) {
        o.clOrdId = nextClOrdId();
        o.rootId = o.clOrdId;
        o.orderId = o.s.idPrefix + "O" + String.format("%07d", ++o.s.orderNo);
        return emit(o.s, true, "D", b -> b.add(11, o.clOrdId).add(1, o.account).add(21, 1)
                .add(55, o.symbol).add(54, sideOverride != null ? sideOverride : String.valueOf(o.side))
                .add(60, Times.fix(clock)).add(38, o.qty).add(40, 2).add(44, px(o.px)).add(59, 0)
                .add(453, 1).add(448, o.trader).add(447, 'D').add(452, 11));
    }

    private String execId(Sess s) {
        return s.idPrefix + "E" + String.format("%07d", ++s.execNo);
    }

    /** Sends an ExecutionReport with exactly the given quantities, right or wrong. */
    private void er(Ord o, String clOrdId, String origClOrdId, String execId, char execType, char ordStatus,
                    long orderQty, long lastQty, long cumQty, long leavesQty, Consumer<FixBuilder> extra) {
        emit(o.s, false, "8", b -> {
            b.add(37, o.orderId).add(11, clOrdId);
            if (origClOrdId != null) b.add(41, origClOrdId);
            b.add(17, execId).add(150, execType).add(39, ordStatus).add(1, o.account)
                    .add(55, o.symbol).add(54, o.side).add(38, orderQty).add(40, 2).add(44, px(o.px));
            if (lastQty > 0) b.add(32, lastQty).add(31, px(o.px));
            b.add(151, leavesQty).add(14, cumQty).add(6, cumQty > 0 ? px(o.px) : "0").add(60, Times.fix(clock));
            if (extra != null) extra.accept(b);
        });
    }

    private void ack(Ord o) {
        er(o, o.clOrdId, null, execId(o.s), '0', '0', o.qty, 0, 0, o.qty, null);
    }

    private void fill(Ord o, long qty) {
        o.cum += qty;
        er(o, o.clOrdId, null, execId(o.s), 'F', o.cum >= o.qty ? '2' : '1', o.qty, qty, o.cum, Math.max(0, o.qty - o.cum), null);
    }

    private String cancelRequest(Ord o, String origClOrdId) {
        String id = nextClOrdId();
        emit(o.s, true, "F", b -> b.add(11, id).add(41, origClOrdId).add(55, o.symbol).add(54, o.side)
                .add(60, Times.fix(clock)).add(38, o.qty));
        return id;
    }

    private String replaceRequest(Ord o, long newQty, double newPx) {
        String id = nextClOrdId();
        emit(o.s, true, "G", b -> b.add(11, id).add(41, o.clOrdId).add(1, o.account).add(21, 1).add(55, o.symbol)
                .add(54, o.side).add(60, Times.fix(clock)).add(38, newQty).add(40, 2).add(44, px(newPx)).add(59, 0));
        return id;
    }

    private void cancelReject(Ord o, String clOrdId, String origClOrdId, char ordStatus, char responseTo, int reason, String text) {
        emit(o.s, false, "9", b -> b.add(37, o.orderId == null ? "NONE" : o.orderId).add(11, clOrdId).add(41, origClOrdId)
                .add(39, ordStatus).add(434, responseTo).add(102, reason).add(58, text));
    }

    /** Accepted replace: PendingReplace, then Replaced under the new ClOrdID. Returns the time of the last step. */
    private long replace(Ord o, long t, long newQty, double newPx) {
        String[] id = new String[1];
        at(t, () -> id[0] = replaceRequest(o, newQty, newPx));
        t += between(3, 30);
        at(t, () -> er(o, id[0], o.clOrdId, execId(o.s), 'E', 'E', o.qty, 0, o.cum, o.qty - o.cum, null));
        t += between(2, 20);
        at(t, () -> {
            String old = o.clOrdId;
            o.clOrdId = id[0];
            o.qty = newQty;
            o.px = newPx;
            er(o, o.clOrdId, old, execId(o.s), '5', o.cum > 0 ? '1' : '0', o.qty, 0, o.cum, o.qty - o.cum, null);
        });
        return t;
    }

    /** Fills {@code total} in one to three trades. Returns the time of the last one. */
    private long fills(Ord o, long t, long total) {
        int n = 1 + rnd.nextInt(3);
        long lots = total / 100;
        long left = lots;
        for (int i = 0; i < n && left > 0; i++) {
            long part = i == n - 1 ? left : Math.max(1, Math.min(left, 1 + rnd.nextInt((int) Math.max(1, lots / n))));
            if (i == n - 1) part = left;
            left -= part;
            long q = part * 100;
            t += between(200, 5000);
            at(t, () -> fill(o, q));
        }
        return t;
    }

    private void normalOrder(Sess s, long t0) {
        Ord o = randomOrder(s);
        int kind = rnd.nextInt(100);
        long t = t0;
        at(t, () -> nos(o, null));
        t += between(3, 40);
        at(t, () -> ack(o));
        if (kind < 55) {
            fills(o, t, o.qty);
        } else if (kind < 78) {
            long first = o.qty >= 200 && rnd.nextBoolean() ? 100L * (1 + rnd.nextInt((int) (o.qty / 100) - 1)) : 0;
            if (first > 0) {
                t += between(200, 4000);
                at(t, () -> fill(o, first));
            }
            t += between(500, 4000);
            String[] id = new String[1];
            at(t, () -> id[0] = cancelRequest(o, o.clOrdId));
            t += between(3, 30);
            at(t, () -> er(o, id[0], o.clOrdId, execId(s), '6', '6', o.qty, 0, o.cum, o.qty - o.cum, null));
            t += between(2, 20);
            at(t, () -> er(o, id[0], o.clOrdId, execId(s), '4', '4', o.qty, 0, o.cum, 0, null));
        } else {
            long newQty = o.qty + 100L * (1 + rnd.nextInt(5));
            t += between(300, 3000);
            t = replace(o, t, newQty, o.px);
            fills(o, t, newQty);
        }
    }

    // ------------------------------------------------------------------ planted breaks

    private void plant(String ruleId, Sess s, String clOrdId, String note) {
        planted.add(new Planted(ruleId, s == null ? null : s.name(), clOrdId, note));
    }

    private void plantBreaks(Sess x, Sess y, long start, long outageStart, long outageEnd) {
        final long min = 60_000L;

        // ORDER_REJECTED: order breaches the broker's notional limit.
        Ord rej = order(x, "UMBRELLA", '1', 50_000, 310.00);
        at(start + 4 * min, () -> {
            nos(rej, null);
            plant("ORDER_REJECTED", x, rej.rootId, "Notional 15.5m exceeds the broker's 10m per-order limit");
        });
        at(start + 4 * min + 18, () -> emit(x, false, "8", b -> b.add(37, "NONE").add(11, rej.clOrdId).add(17, execId(x))
                .add(150, '8').add(39, '8').add(1, rej.account).add(55, rej.symbol).add(54, rej.side).add(38, rej.qty)
                .add(40, 2).add(44, px(rej.px)).add(151, 0).add(14, 0).add(6, "0").add(60, Times.fix(clock)).add(103, 3)
                .add(58, "Order exceeds limit: notional 15500000.00 > max 10000000.00 for account " + rej.account)));

        // SESSION_REJECT: NewOrderSingle with an invalid Side(54).
        Ord bad = order(x, "INITECH", '1', 700, 23.15);
        at(start + 7 * min, () -> {
            long seq = nos(bad, "Z");
            plant("SESSION_REJECT", x, bad.rootId, "NewOrderSingle sent with Side(54)=Z, refused by the broker's FIX engine");
            at(clock + 9, () -> emit(x, false, "3", b -> b.add(45, seq).add(371, 54).add(372, "D").add(373, 5)
                    .add(58, "Value is incorrect (out of range) for this tag")));
        });

        // BUSINESS_REJECT: broker's order router is down.
        Ord biz = order(x, "HOOLI", '2', 1200, 76.50);
        at(start + 10 * min, () -> {
            long seq = nos(biz, null);
            plant("BUSINESS_REJECT", x, biz.rootId, "Broker application could not route the order");
            at(clock + 14, () -> emit(x, false, "j", b -> b.add(45, seq).add(372, "D").add(379, biz.clOrdId).add(380, 4)
                    .add(58, "Order routing unavailable")));
        });

        // PARSE_ERROR: one heartbeat with a corrupted checksum.
        at(start + 12 * min, () -> {
            emit(x, false, "0", -1, null, DAMAGE_CHECKSUM);
            plant("PARSE_ERROR", null, null, "Heartbeat from BROKERX with a wrong CheckSum(10)");
        });

        // CANCEL_REPLACE_REJECTED: cancel arrives after the order is fully filled.
        Ord late = order(x, "WAYNE", '1', 400, 54.80);
        at(start + 13 * min, () -> {
            nos(late, null);
            plant("CANCEL_REPLACE_REJECTED", x, late.rootId, "Cancel request crossed with the final fill");
        });
        at(start + 13 * min + 20, () -> ack(late));
        at(start + 13 * min + 900, () -> fill(late, 400));
        at(start + 13 * min + 980, () -> {
            String id = cancelRequest(late, late.clOrdId);
            at(clock + 11, () -> cancelReject(late, id, late.clOrdId, '2', '1', 0, "Too late to cancel"));
        });

        // SEQ_GAP (recovered): two BROKERX messages lost, resend requested, gap filled.
        at(start + 15 * min, () -> {
            long gapStart = x.brokerSeq;
            x.brokerSeq += 2;
            emit(x, false, "0", null);
            plant("SEQ_GAP", x, null, "Two messages from BROKERX missing; recovered with ResendRequest and SequenceReset-GapFill");
            clock += 4;
            emit(x, true, "2", b -> b.add(7, gapStart).add(16, 0));
            clock += 9;
            long next = x.brokerSeq;
            emit(x, false, "4", gapStart, b -> b.add(43, "Y").add(122, Times.fix(clock)).add(123, "Y").add(36, next), DAMAGE_NONE);
        });

        // NO_ACK: order sent, broker never answers.
        Ord silent = order(x, "STARK", '1', 300, 198.55);
        at(start + 16 * min, () -> {
            nos(silent, null);
            plant("NO_ACK", x, silent.rootId, "NewOrderSingle never acknowledged");
        });

        // STUCK_PARTIAL: partial fill, then nothing.
        Ord stuck = order(x, "GLOBEX", '2', 1000, 112.40);
        at(start + 19 * min, () -> {
            nos(stuck, null);
            plant("STUCK_PARTIAL", x, stuck.rootId, "Filled 300 of 1000, no further reports");
        });
        at(start + 19 * min + 25, () -> ack(stuck));
        at(start + 19 * min + 2_300, () -> fill(stuck, 300));

        // SEQ_GAP (unrecovered) on BROKERY: a 95 s outage (115 s with no message at all, counting the idle time before it), three messages lost, ResendRequest ignored.
        at(outageStart, () -> y.brokerSilent = true);
        at(outageStart + 62_000, () -> emit(y, true, "1", b -> b.add(112, "TEST-" + Times.fix(clock).substring(9, 17).replace(":", ""))));
        at(outageEnd, () -> {
            long gapStart = y.brokerSeq;
            y.brokerSeq += 3;
            y.brokerSilent = false;
            emit(y, false, "0", null);
            plant("SEQ_GAP", y, null, "No message from BROKERY for 115s, then three messages missing; ResendRequest never answered");
            clock += 5;
            emit(y, true, "2", b -> b.add(7, gapStart).add(16, 0));
        });

        // OVERFILL: 500 ordered, 600 filled.
        Ord over = order(x, "ACME", '1', 500, 48.25);
        at(start + 22 * min, () -> {
            nos(over, null);
            plant("OVERFILL", x, over.rootId, "Two fills of 300 against an order for 500");
        });
        at(start + 22 * min + 15, () -> ack(over));
        at(start + 22 * min + 1_200, () -> fill(over, 300));
        at(start + 22 * min + 1_950, () -> fill(over, 300));

        // QTY_MISMATCH: CumQty jumps by more than the reported LastQty.
        Ord mism = order(x, "SOYLENT", '2', 1000, 9.85);
        at(start + 25 * min, () -> {
            nos(mism, null);
            plant("QTY_MISMATCH", x, mism.rootId, "Second fill reports LastQty 300 but CumQty 600 after a first fill of 200");
        });
        at(start + 25 * min + 22, () -> ack(mism));
        at(start + 25 * min + 1_500, () -> fill(mism, 200));
        at(start + 25 * min + 3_100, () -> er(mism, mism.clOrdId, null, execId(x), 'F', '1', 1000, 300, 600, 400, null));
        at(start + 25 * min + 5_400, () -> er(mism, mism.clOrdId, null, execId(x), 'F', '2', 1000, 400, 1000, 0, null));

        // DUPLICATE_EXEC: same ExecID sent twice without PossDupFlag.
        Ord dup = order(x, "HOOLI", '1', 600, 76.40);
        String[] dupExec = new String[1];
        at(start + 28 * min, () -> {
            nos(dup, null);
            plant("DUPLICATE_EXEC", x, dup.rootId, "First fill of 200 sent twice with the same ExecID");
        });
        at(start + 28 * min + 30, () -> ack(dup));
        at(start + 28 * min + 1_000, () -> {
            dupExec[0] = execId(x);
            er(dup, dup.clOrdId, null, dupExec[0], 'F', '1', 600, 200, 200, 400, null);
        });
        at(start + 28 * min + 1_150, () -> er(dup, dup.clOrdId, null, dupExec[0], 'F', '1', 600, 200, 200, 400, null));
        at(start + 28 * min + 4_000, () -> er(dup, dup.clOrdId, null, execId(x), 'F', '2', 600, 400, 600, 0, null));

        // PARSE_ERROR: one line cut short.
        at(start + 30 * min, () -> {
            emit(y, true, "0", -1, null, DAMAGE_TRUNCATE);
            plant("PARSE_ERROR", null, null, "Heartbeat to BROKERY truncated mid-line");
        });

        // BROKEN_CHAIN: cancel for a ClOrdID that was never sent.
        Ord ghost = order(x, "STARK", '2', 500, 198.70);
        at(start + 31 * min, () -> {
            String unknown = "ORD-900001";
            String id = cancelRequest(ghost, unknown);
            plant("BROKEN_CHAIN", x, unknown, "Cancel request refers to an OrigClOrdID that is not in the log");
            plant("CANCEL_REPLACE_REJECTED", x, unknown, "...and the broker rejects it as an unknown order");
            at(clock + 8, () -> cancelReject(ghost, id, unknown, '8', '1', 1, "Unknown order"));
        });

        // INVALID_STATE_TRANSITION: a fill arrives after the order was canceled.
        Ord zombie = order(x, "INITECH", '1', 1000, 23.05);
        String[] zCancel = new String[1];
        at(start + 34 * min, () -> {
            nos(zombie, null);
            plant("INVALID_STATE_TRANSITION", x, zombie.rootId, "Fill of 100 reported two seconds after the order was canceled");
        });
        at(start + 34 * min + 20, () -> ack(zombie));
        at(start + 34 * min + 1_800, () -> fill(zombie, 400));
        at(start + 34 * min + 4_000, () -> zCancel[0] = cancelRequest(zombie, zombie.clOrdId));
        at(start + 34 * min + 4_015, () -> er(zombie, zCancel[0], zombie.clOrdId, execId(x), '6', '6', 1000, 0, 400, 600, null));
        at(start + 34 * min + 4_030, () -> er(zombie, zCancel[0], zombie.clOrdId, execId(x), '4', '4', 1000, 0, 400, 0, null));
        at(start + 34 * min + 6_100, () -> er(zombie, zombie.clOrdId, null, execId(x), 'F', '1', 1000, 100, 500, 500, null));

        // The tricky one: amended twice, final cancel rejected, then partially filled and left hanging.
        Ord tricky = order(x, "ACME", '2', 1000, 48.30);
        long t = start + 37 * min;
        at(t, () -> {
            nos(tricky, null);
            plant("CANCEL_REPLACE_REJECTED", x, tricky.rootId, "Amend chain: the final cancel is rejected as too late (closing auction)");
            plant("STUCK_PARTIAL", x, tricky.rootId, "...and the order is then filled 600 of 1500 with no terminal state");
        });
        at(t + 20, () -> ack(tricky));
        t = replace(tricky, t + 3_000, 1500, 48.30);
        t = replace(tricky, t + 4_000, 1500, 48.40);
        long cancelAt = t + 5_000;
        at(cancelAt, () -> {
            String id = cancelRequest(tricky, tricky.clOrdId);
            at(clock + 12, () -> cancelReject(tricky, id, tricky.clOrdId, '0', '1', 0, "Too late to cancel: order is in closing auction"));
        });
        at(cancelAt + 450, () -> fill(tricky, 600));
    }
}
