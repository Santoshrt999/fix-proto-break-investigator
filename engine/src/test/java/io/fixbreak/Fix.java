package io.fixbreak;

import io.fixbreak.gen.FixBuilder;
import io.fixbreak.parser.FixLineParser;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.util.Times;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test helper that scripts a FIX conversation: it assigns sequence numbers per direction,
 * advances a clock, and keeps every line so a test can either feed parsed messages straight
 * into a tracker or write the lines to a log file.
 */
public final class Fix {
    public static final String CLIENT = "BUYSIDE1";
    public static final String BROKER = "BROKERX";

    private final FixLineParser parser = new FixLineParser();
    private final Map<String, Long> nextSeq = new HashMap<>();
    private final List<String> lines = new ArrayList<>();
    private long clock = Times.parseLoose("2026-01-05T09:30:00");

    /** Moves the clock forward. Every message also advances it by 10 ms. */
    public Fix after(long millis) {
        clock += millis;
        return this;
    }

    public long now() {
        return clock;
    }

    public List<String> lines() {
        return lines;
    }

    /** Skips sequence numbers in one direction, as if those messages never reached the log. */
    public Fix lose(String sender, String target, int count) {
        String key = sender + ">" + target;
        nextSeq.put(key, nextSeq.getOrDefault(key, 1L) + count);
        return this;
    }

    /** Both sides restart their sequence numbers at 1, as after a ResetSeqNumFlag Logon or a new day. */
    public Fix resetSequences() {
        nextSeq.clear();
        return this;
    }

    public long peekSeq(String sender, String target) {
        return nextSeq.getOrDefault(sender + ">" + target, 1L);
    }

    /** Client to broker message with the next sequence number. Fields are tag, value pairs. */
    public FixMessage out(String type, Object... fields) {
        return send(CLIENT, BROKER, type, fields);
    }

    /** Broker to client message with the next sequence number. */
    public FixMessage in(String type, Object... fields) {
        return send(BROKER, CLIENT, type, fields);
    }

    public FixMessage send(String sender, String target, String type, Object... fields) {
        String key = sender + ">" + target;
        long seq = nextSeq.getOrDefault(key, 1L);
        nextSeq.put(key, seq + 1);
        return sendSeq(sender, target, seq, type, fields);
    }

    /** Message with an explicit sequence number that does not advance the counter (resends, gap fills). */
    public FixMessage sendSeq(String sender, String target, long seq, String type, Object... fields) {
        clock += 10;
        FixBuilder b = new FixBuilder("FIX.4.4", type)
                .add(49, sender).add(56, target).add(34, seq).add(52, Times.fix(clock));
        for (int i = 0; i < fields.length; i += 2) {
            b.add((Integer) fields[i], String.valueOf(fields[i + 1]));
        }
        return addLine(b.build("|"));
    }

    /** Adds an arbitrary line (corrupt, truncated, non-FIX) and returns the parsed message if there is one. */
    public FixMessage addLine(String line) {
        lines.add(line);
        FixLineParser.Result r = new FixLineParser.Result();
        parser.parse(line, 0, lines.size(), r);
        return r.msg;
    }

    // ---- common order flow, so tests read like a conversation ----

    public FixMessage newOrder(String clOrdId, String symbol, int qty, String price) {
        return out("D", 11, clOrdId, 1, "ACC90210", 21, 1, 55, symbol, 54, 1, 60, Times.fix(clock), 38, qty, 40, 2, 44, price, 59, 0);
    }

    public FixMessage ack(String clOrdId, int qty) {
        return exec(clOrdId, null, '0', '0', qty, 0, 0, qty);
    }

    public FixMessage fill(String clOrdId, int qty, int lastQty, int cumQty) {
        int leaves = Math.max(0, qty - cumQty);
        return exec(clOrdId, null, 'F', leaves == 0 ? '2' : '1', qty, lastQty, cumQty, leaves);
    }

    private int execCounter;

    /** ExecutionReport with a fresh ExecID. Pass {@code origClOrdId} null to omit tag 41. */
    public FixMessage exec(String clOrdId, String origClOrdId, char execType, char ordStatus,
                           int orderQty, int lastQty, int cumQty, int leavesQty) {
        return execWithId("E" + (++execCounter), clOrdId, origClOrdId, execType, ordStatus, orderQty, lastQty, cumQty, leavesQty);
    }

    public FixMessage execWithId(String execId, String clOrdId, String origClOrdId, char execType, char ordStatus,
                                 int orderQty, int lastQty, int cumQty, int leavesQty, Object... extra) {
        List<Object> f = new ArrayList<>(List.of(37, "BX-" + clOrdId.replaceAll("\\D", ""), 11, clOrdId));
        if (origClOrdId != null) {
            f.add(41);
            f.add(origClOrdId);
        }
        f.addAll(List.of(17, execId, 150, execType, 39, ordStatus, 55, "ACME", 54, 1, 38, orderQty));
        if (lastQty > 0) f.addAll(List.of(32, lastQty, 31, "10.00"));
        f.addAll(List.of(151, leavesQty, 14, cumQty, 6, cumQty > 0 ? "10.00" : "0"));
        f.addAll(List.of(extra));
        return in("8", f.toArray());
    }

    /** Logon from the client and the broker's Logon reply, HeartBtInt 30s. */
    public List<FixMessage> logon() {
        return List.of(out("A", 98, 0, 108, 30), in("A", 98, 0, 108, 30));
    }
}
