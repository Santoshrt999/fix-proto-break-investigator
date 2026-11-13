package io.fixbreak.order;

import io.fixbreak.Fix;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.session.SessionTracker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fixbreak.Fix.BROKER;
import static io.fixbreak.Fix.CLIENT;
import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {
    private final Fix fix = new Fix();
    private final SessionTracker sessions = new SessionTracker();
    private final OrderBook book = new OrderBook();

    private OrderUpdate feed(FixMessage m) {
        SessionTracker.Update su = sessions.onMessage(m);
        if (su.duplicateResend) return null;
        return book.onMessage(m, su);
    }

    private OrderState only() {
        assertEquals(1, book.orders().size());
        return book.orders().get(0);
    }

    @Test
    void simpleLifecycle() {
        feed(fix.newOrder("A1", "ACME", 1000, "10.00"));
        OrderUpdate ack = feed(fix.ack("A1", 1000));
        assertTrue(ack.firstExec);
        assertFalse(ack.trade);
        OrderUpdate f1 = feed(fix.fill("A1", 1000, 400, 400));
        assertTrue(f1.trade);
        assertEquals('0', f1.prevStatus);
        assertFalse(f1.firstExec);
        feed(fix.fill("A1", 1000, 600, 1000));

        OrderState o = only();
        assertTrue(o.seenNew());
        assertEquals('2', o.status);
        assertTrue(o.terminal());
        assertEquals(1000, o.cumQty);
        assertEquals(1000, o.sumLastQty);
        assertEquals(0, o.leavesQty);
        assertEquals(1000, o.orderQty);
        assertEquals("ACME", o.symbol);
        assertEquals("BX-1", o.orderId);
        assertEquals(4, o.refCount());
        assertEquals(List.of("A1"), o.chain());
        assertSame(o, book.find("A1").get(0));
        assertSame(o, book.find("BX-1").get(0));
        assertTrue(book.find("nope").isEmpty());
    }

    @Test
    void amendChainIsOneOrder() {
        feed(fix.newOrder("A1", "ACME", 1000, "10.00"));
        feed(fix.ack("A1", 1000));
        feed(fix.out("G", 11, "A2", 41, "A1", 55, "ACME", 54, 1, 38, 1500, 40, 2, 44, "10.00"));
        assertEquals(1, only().pending().size());
        feed(fix.exec("A2", "A1", 'E', 'E', 1000, 0, 0, 1000));
        feed(fix.exec("A2", "A1", '5', '0', 1500, 0, 0, 1500));
        feed(fix.out("F", 11, "A3", 41, "A2", 55, "ACME", 54, 1));
        feed(fix.exec("A3", "A2", '4', '4', 1500, 0, 0, 0));

        OrderState o = only();
        assertEquals(List.of("A1", "A2", "A3"), o.chain());
        assertEquals(1500, o.orderQty);
        assertEquals('4', o.status);
        assertTrue(o.pending().isEmpty());
        assertSame(o, book.find("A2").get(0));
        assertEquals("A2", o.currentClOrdId);
    }

    @Test
    void cancelRejectClearsThePendingRequest() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        feed(fix.ack("A1", 100));
        FixMessage cancel = fix.out("F", 11, "A2", 41, "A1", 55, "ACME", 54, 1);
        feed(cancel);
        OrderUpdate rej = feed(fix.in("9", 37, "BX-1", 11, "A2", 41, "A1", 39, 0, 434, 1, 102, 0));
        assertEquals('9', rej.msgType);
        assertEquals(cancel.ref(), rej.referencedRef);
        assertEquals('F', rej.referencedMsgType);
        assertTrue(only().pending().isEmpty());
        assertEquals('0', only().status, "a cancel reject does not change the order's status");
    }

    @Test
    void unknownOrigClOrdIdIsReportedOncePerId() {
        OrderUpdate u = feed(fix.out("F", 11, "C2", 41, "GHOST", 55, "ACME", 54, 1));
        assertEquals("GHOST", u.unknownOrigClOrdId);
        assertTrue(u.order.placeholder);
        assertEquals(List.of("GHOST", "C2"), u.order.chain());
        OrderUpdate rej = feed(fix.in("9", 37, "NONE", 11, "C2", 41, "GHOST", 39, 8, 434, 1, 102, 1));
        assertNull(rej.unknownOrigClOrdId);
        OrderUpdate again = feed(fix.out("F", 11, "C3", 41, "GHOST", 55, "ACME", 54, 1));
        assertNull(again.unknownOrigClOrdId);
        assertEquals(1, book.orders().size());
    }

    @Test
    void executionReportWithUnknownOrigIsFlagged() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        OrderUpdate u = feed(fix.exec("A1", "WRONG", '5', '0', 100, 0, 0, 100));
        assertEquals("WRONG", u.unknownOrigClOrdId);
        assertNull(feed(fix.exec("A1", "WRONG", '0', '0', 100, 0, 0, 100)).unknownOrigClOrdId);
    }

    @Test
    void duplicateExecIdIsNotAppliedTwice() {
        feed(fix.newOrder("A1", "ACME", 1000, "10.00"));
        feed(fix.ack("A1", 1000));
        FixMessage first = fix.execWithId("DUP1", "A1", null, 'F', '1', 1000, 300, 300, 700);
        feed(first);
        OrderUpdate dup = feed(fix.execWithId("DUP1", "A1", null, 'F', '1', 1000, 300, 300, 700));
        assertEquals(first.ref(), dup.duplicateExecRef);
        assertFalse(dup.applied);
        assertEquals(300, only().sumLastQty);
        assertEquals(300, only().cumQty);
    }

    @Test
    void possDupAndPossResendRepeatsAreSilentlyIgnored() {
        feed(fix.newOrder("A1", "ACME", 1000, "10.00"));
        feed(fix.execWithId("X1", "A1", null, 'F', '1', 1000, 300, 300, 700));
        // PossResend: new sequence number, same ExecID
        assertNull(feed(fix.execWithId("X1", "A1", null, 'F', '1', 1000, 300, 300, 700, 97, "Y")));
        // PossDup of an already seen sequence number never reaches the book
        assertNull(feed(fix.sendSeq(BROKER, CLIENT, 1, "8", 43, "Y", 11, "A1", 17, "X1", 150, "F", 39, 1, 32, 300, 14, 300, 151, 700)));
        assertEquals(300, only().sumLastQty);
        assertEquals(2, only().refCount());
    }

    @Test
    void terminalStatusIsSticky() {
        feed(fix.newOrder("A1", "ACME", 1000, "10.00"));
        feed(fix.ack("A1", 1000));
        feed(fix.fill("A1", 1000, 400, 400));
        feed(fix.exec("A1", null, '4', '4', 1000, 0, 400, 0));
        OrderUpdate late = feed(fix.exec("A1", null, 'F', '1', 1000, 100, 500, 500));
        assertTrue(late.wasTerminal);
        assertTrue(late.trade);
        assertEquals('4', late.prevStatus);
        assertEquals('4', only().status);
        assertEquals(500, only().cumQty);
    }

    @Test
    void tradeCancelReducesTheRunningTotalAndMayReopenTheOrder() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        feed(fix.fill("A1", 100, 100, 100));
        assertEquals('2', only().status);
        OrderUpdate bust = feed(fix.exec("A1", null, 'H', '1', 100, 40, 60, 40));
        assertTrue(bust.tradeCancel);
        assertFalse(bust.trade);
        assertEquals(60, only().sumLastQty);
        assertEquals('1', only().status);
    }

    @Test
    void fix42FillsCountAsTrades() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        OrderUpdate u = feed(fix.in("8", 37, "O1", 11, "A1", 17, "E9", 20, 0, 150, 1, 39, 1, 55, "ACME", 54, 1, 38, 100, 32, 40, 31, "10", 151, 60, 14, 40, 6, "10"));
        assertTrue(u.trade);
        OrderUpdate status = feed(fix.in("8", 37, "O1", 11, "A1", 17, "E10", 20, 3, 150, 1, 39, 1, 55, "ACME", 54, 1, 38, 100, 32, 40, 151, 60, 14, 40, 6, "10"));
        assertFalse(status.trade, "ExecTransType=3 is a status report, not a new fill");
        assertEquals(40, only().sumLastQty);
    }

    @Test
    void sessionRejectIsLinkedThroughRefSeqNum() {
        FixMessage nos = fix.newOrder("A1", "ACME", 100, "10.00");
        feed(nos);
        OrderUpdate u = feed(fix.in("3", 45, nos.getLong(34, -1), 371, 54, 372, "D", 373, 5, 58, "Value is incorrect"));
        assertSame(only(), u.order);
        assertEquals(nos.ref(), u.referencedRef);
        assertEquals('D', u.referencedMsgType);
        assertTrue(only().rejectedBeforeAck);
    }

    @Test
    void businessRejectFallsBackToRefId() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        OrderUpdate u = feed(fix.in("j", 372, "D", 379, "A1", 380, 4, 58, "Application not available"));
        assertSame(only(), u.order);
        assertTrue(only().rejectedBeforeAck);
    }

    @Test
    void rejectForSomethingElseHasNoOrder() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        OrderUpdate u = feed(fix.in("3", 45, 999, 373, 1));
        assertNotNull(u);
        assertNull(u.order);
        assertFalse(only().rejectedBeforeAck);
    }

    @Test
    void executionReportWithoutNewOrderCreatesAnOrphan() {
        OrderUpdate u = feed(fix.fill("MID1", 100, 50, 50));
        assertFalse(u.order.seenNew());
        assertFalse(u.order.placeholder);
        assertNull(u.unknownOrigClOrdId);
    }

    @Test
    void sameClOrdIdInDifferentSessionsAreDifferentOrders() {
        feed(fix.send("C1", "B1", "D", 11, "1", 55, "ACME", 54, 1, 38, 10, 40, 1));
        feed(fix.send("C2", "B2", "D", 11, "1", 55, "ACME", 54, 1, 38, 20, 40, 1));
        assertEquals(2, book.orders().size());
        assertEquals(2, book.find("1").size());
    }

    @Test
    void duplicateNewOrderIsNoted() {
        feed(fix.newOrder("A1", "ACME", 100, "10.00"));
        assertTrue(feed(fix.newOrder("A1", "ACME", 100, "10.00")).duplicateClOrdId);
        assertEquals(1, book.orders().size());
    }
}
