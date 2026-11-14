package io.fixbreak.order;

/**
 * What one order-related message did to an order. Filled in by {@link OrderBook} and read by
 * the rules; the instance is reused for every message, so rules must not keep it.
 */
public final class OrderUpdate {
    /** The order the message belongs to. Null for a Reject(3)/BusinessMessageReject(j) that could not be tied to an order. */
    public OrderState order;
    /** MsgType of the message: D, G, F, 8, 9, 3 or j. */
    public char msgType;
    public String clOrdId;
    public String origClOrdId;

    /** False when the message was recognised as a repeat and deliberately not applied to the order's state. */
    public boolean applied;

    // --- ExecutionReport (8) ---
    public char execType;
    public char ordStatus;
    /** A fill or partial fill (ExecType F, or 1/2 in FIX 4.2). */
    public boolean trade;
    public boolean tradeCancel;
    public boolean tradeCorrect;
    /** Values carried on this report; NaN when the tag is absent. */
    public double lastQty;
    public double cumQty;
    public double leavesQty;
    /** OrderQty the report should be measured against: tag 38 on the report, else the order's known quantity. */
    public double orderQty;
    /** Order state just before this report was applied. */
    public char prevStatus;
    public double prevCumQty;
    public boolean wasTerminal;
    /** True for the first ExecutionReport the order ever received. */
    public boolean firstExec;
    /** Line of the earlier report with the same ExecID(17), when this one is not flagged PossDup/PossResend. 0 otherwise. */
    public long duplicateExecRef;

    // --- chains ---
    /** An OrigClOrdID(41) on this message that the log never introduced. Reported once per unknown id. */
    public String unknownOrigClOrdId;
    /** A NewOrderSingle reused a ClOrdID that already exists in this session. */
    public boolean duplicateClOrdId;

    // --- rejects (9, 3, j) ---
    /** Line of the request this reject answers, 0 when it could not be located. */
    public long referencedRef;
    /** MsgType of that request (D, G or F), 0 when unknown. */
    public char referencedMsgType;

    void reset(char msgType) {
        this.msgType = msgType;
        order = null;
        clOrdId = null;
        origClOrdId = null;
        applied = true;
        execType = 0;
        ordStatus = 0;
        trade = false;
        tradeCancel = false;
        tradeCorrect = false;
        lastQty = Double.NaN;
        cumQty = Double.NaN;
        leavesQty = Double.NaN;
        orderQty = Double.NaN;
        prevStatus = 0;
        prevCumQty = 0;
        wasTerminal = false;
        firstExec = false;
        duplicateExecRef = 0;
        unknownOrigClOrdId = null;
        duplicateClOrdId = false;
        referencedRef = 0;
        referencedMsgType = 0;
    }
}
