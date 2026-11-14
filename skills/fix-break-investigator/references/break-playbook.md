# Break playbook

One section per `ruleId`. For each: what you see, what usually causes it, how to confirm with
`fixscan`, and who normally owns the fix. Causes are ordered most common first. They are
starting hypotheses, not conclusions: confirm with log lines before reporting.

"Counterparty" means the other end of the session (broker, venue or client, depending on whose
log this is).

## Contents

- [ORDER_REJECTED](#order_rejected)
- [SESSION_REJECT](#session_reject)
- [BUSINESS_REJECT](#business_reject)
- [CANCEL_REPLACE_REJECTED](#cancel_replace_rejected)
- [NO_ACK](#no_ack)
- [STUCK_PARTIAL](#stuck_partial)
- [OVERFILL](#overfill)
- [QTY_MISMATCH](#qty_mismatch)
- [DUPLICATE_EXEC](#duplicate_exec)
- [BROKEN_CHAIN](#broken_chain)
- [SEQ_GAP](#seq_gap)
- [INVALID_STATE_TRANSITION](#invalid_state_transition)
- [PARSE_ERROR](#parse_error)
- [Combinations worth recognising](#combinations-worth-recognising)

---

## ORDER_REJECTED

**Symptom.** ExecutionReport (35=8) with ExecType(150)=8 or OrdStatus(39)=8. The reason is in
OrdRejReason(103) and usually Text(58).

**Likely causes, by OrdRejReason(103).**

| 103 | Meaning | Usual cause |
| --- | --- | --- |
| 0 | Broker / Exchange option | Catch-all. Text(58) carries the real reason; read it literally |
| 1 | Unknown symbol | Symbol not set up at the counterparty, wrong symbology (RIC vs ticker), expired or delisted instrument |
| 2 | Exchange closed | Order sent outside trading hours, on a holiday, or during a halt |
| 3 | Order exceeds limit | Quantity or notional above a per-order, per-account or fat-finger limit |
| 4 | Too late to enter | Past a cut-off (auction, close, expiry) |
| 5 | Unknown order | Referenced order does not exist at the counterparty |
| 6 | Duplicate order | ClOrdID reused; often a resend after a reconnect |
| 8 | Stale order | TransactTime / SendingTime too old; clock drift or a queued order released late |
| 11 | Unsupported order characteristic | OrdType, TimeInForce, ExecInst or a combination the venue does not support |
| 13 | Incorrect quantity | Not a multiple of the lot size, below minimum, zero |
| 15 | Unknown account(s) | Account not set up or not mapped at the counterparty |
| 16, 18 | Price band / price increment | Price outside the allowed band, or not on the tick |
| 99 | Other | See Text(58) |

**Check next.**
- `fixscan order <ClOrdID>`: compare the order's terms (38, 44, 40, 59, 55, 1) with what the
  reject text complains about. Work the numbers: for a limit breach, OrderQty x Price vs the
  limit quoted in the text.
- `fixscan grep 35=8,103=<reason>`: is it one order or many? Many with the same reason points to
  static data or a limit, not the individual order.
- `fixscan grep 55=<symbol>,35=8 --limit 5`: did other orders in the same symbol succeed? If yes,
  the symbol is fine and the difference is in this order's terms.
- For reason 6: `fixscan grep 11=<ClOrdID>` to see whether the ClOrdID was sent twice.

**Who to contact.** Limit breaches: risk / the desk that owns the limit, and the client if the
size was intended. Unknown symbol or account: static data team (yours and the counterparty's).
Exchange closed / too late: trading desk or client (timing), ops if a queued order was released
late. Unsupported characteristic: FIX connectivity / onboarding team (check the counterparty's
rules of engagement).

---

## SESSION_REJECT

**Symptom.** Reject (35=3) from the counterparty's FIX engine. RefSeqNum(45) names the offending
message, RefTagID(371) the tag, RefMsgType(372) its type, SessionRejectReason(373) why.
The business application never saw the message, so there will be no ExecutionReport for it.

**Likely causes, by SessionRejectReason(373).**

| 373 | Meaning | Usual cause |
| --- | --- | --- |
| 1 | Required tag missing | Sender omits a field the counterparty's dictionary requires |
| 2, 3 | Tag not defined for this message / undefined tag | Custom tag the counterparty does not know; dictionary version mismatch |
| 4 | Tag without a value | Empty field: upstream system passed a blank |
| 5 | Value is incorrect (out of range) | Enum value the counterparty does not accept (e.g. a Side, OrdType or TimeInForce not in their spec) |
| 6 | Incorrect data format | Wrong type or precision: decimals in an integer field, bad timestamp format |
| 9 | CompID problem | SenderCompID / TargetCompID / SubID do not match the session configuration |
| 10 | SendingTime accuracy | Clock skew between the two hosts (NTP) |
| 11 | Invalid MsgType | Message type not supported on this session |
| 13, 14, 15, 16 | Tag repeated / out of order / repeating group errors | Message built incorrectly, usually a repeating group count (e.g. NoPartyIDs) that does not match |

**Check next.**
- The finding's evidence already includes the rejected message when it could be located. Look
  at the tag named by RefTagID(371) in that line: what value was sent?
- `fixscan explain-tag <RefTagID>` then `fixscan explain-tag <RefTagID> <value sent>`: is the
  value valid FIX at all, or valid FIX that this counterparty does not support?
- `fixscan grep 35=3`: one reject or many? Many rejects for the same tag means a systemic
  mapping or dictionary problem, typically after a release on either side.
- `fixscan grep <tag>=<bad value>`: which other messages carry the same bad value.

**Who to contact.** Your FIX connectivity / application team first: a session reject nearly
always means your side sent something the agreed spec does not allow. If the message was valid
per the agreed rules of engagement, the counterparty's connectivity team. The originating
system's owner if a bad value came from upstream (OMS, client).

---

## BUSINESS_REJECT

**Symptom.** BusinessMessageReject (35=j). The message was valid FIX but the application behind
the FIX engine would not process it. BusinessRejectReason(380), RefMsgType(372), RefSeqNum(45),
BusinessRejectRefID(379), Text(58).

**Likely causes, by BusinessRejectReason(380).**

| 380 | Meaning | Usual cause |
| --- | --- | --- |
| 0 | Other | See Text(58) |
| 1 | Unknown ID | The referenced order, quote or request id does not exist there |
| 2 | Unknown Security | Instrument not known to the application (static data) |
| 3 | Unsupported Message Type | Message type valid on the session but not handled by that application |
| 4 | Application not available | Counterparty's order router, matching engine or downstream system is down or not yet started |
| 5 | Conditionally required field missing | A field needed for this kind of order was omitted (e.g. Price on a limit order) |
| 6 | Not authorized | Entitlements: the sender, trader or account is not permissioned for this flow |

**Check next.**
- `fixscan grep 35=j`: how many, over what time span? A cluster in time with reason 4 is an
  outage window at the counterparty; note first and last time.
- `fixscan scan <files> --since <start> --until <end>`: what else happened in that window
  (NO_ACK, SEQ_GAP, heartbeat silence) to size the impact.
- `fixscan order <ClOrdID>`: was the order resubmitted later under a new ClOrdID and accepted?
- For reason 5: compare with a healthy order of the same type to spot the missing field.

**Who to contact.** Reason 4: counterparty support (ask for their incident window) and your
trading ops to deal with affected orders. Reason 2: static data. Reason 6: onboarding /
entitlements at the counterparty. Reasons 3 and 5: your FIX application team.

---

## CANCEL_REPLACE_REJECTED

**Symptom.** OrderCancelReject (35=9). CxlRejResponseTo(434) says whether a cancel (1) or a
cancel/replace (2) was refused; CxlRejReason(102) why; OrdStatus(39) is the order's status at
the time. **The original order is unchanged and may still be live.**

**Likely causes, by CxlRejReason(102).**

| 102 | Meaning | Usual cause |
| --- | --- | --- |
| 0 | Too late to cancel | Order already filled, or in a phase where cancels are not accepted (auction, close) |
| 1 | Unknown order | OrigClOrdID(41) not recognised: wrong id, already replaced, or the order never arrived |
| 2 | Broker / Exchange option | See Text(58): venue rule, minimum resting time, locked order |
| 3 | Already pending cancel or replace | A previous request is still in flight; requests sent too quickly back to back |
| 6 | Duplicate ClOrdID | The request reused a ClOrdID |
| 18 | Invalid price increment | Replace price not on the tick |

**Check next.**
- `fixscan order <any ClOrdID in the chain>`: read the timeline around the reject.
  - Was the order filled just before the request? Then it is a race and reason 0 is expected.
  - Did the request use the *latest* accepted ClOrdID as OrigClOrdID(41)? After an accepted
    replace, the old id is dead; pointing at it gives reason 1.
  - Is there an unanswered earlier request (`unansweredRequests`)? That explains reason 3.
- Look at OrdStatus(39) on the reject and at what happens **after** it. If fills arrive after a
  rejected cancel, the client may believe the order was cancelled when it was not: check for
  STUCK_PARTIAL or unexpected fills on the same chain.
- For reason 1 with BROKEN_CHAIN on the same chain: the request referred to an id the log never
  shows. See [BROKEN_CHAIN](#broken_chain).

**Who to contact.** Trading desk / client to confirm they know the order is still live (or was
filled). Counterparty support if the reject reason contradicts the order's visible state. Your
OMS / FIX application team if the request used a stale or wrong OrigClOrdID.

---

## NO_ACK

**Symptom.** A NewOrderSingle (35=D) with no ExecutionReport within the timeout (HIGH: never
within the log; MEDIUM: arrived late).

**Likely causes.**
1. The counterparty never received it: session was down, disconnected, or in recovery.
2. The counterparty received it but its application is stalled or slow (queueing, outage).
3. The acknowledgement was sent but lost: there is a sequence gap in the inbound direction.
4. The order was rejected at session or business level instead (then SESSION_REJECT or
   BUSINESS_REJECT is reported, not NO_ACK).
5. The log is incomplete: the reply is in another file or was rotated away.

**Check next.**
- `fixscan session <session>`: any heartbeat silence, unanswered TestRequest, sequence gap or
  logout around the order's time? A gap in the counterparty-to-you direction just after the
  order supports cause 3; silence supports 1 or 2.
- `fixscan grep 35=D --since <t-1m> --until <t+1m> --session <session>` and compare with
  `grep 35=8` over the same window: were other orders acknowledged at that time? If they were,
  the session was healthy and the problem is specific to this order.
- `fixscan order <ClOrdID>`: confirm nothing at all came back, including a late reject.
- `fixscan stats`: does the log continue well past the order? If it ends within seconds, it
  proves nothing.

**Who to contact.** Counterparty support: ask whether they received MsgSeqNum N (quote the
sequence number and SendingTime from the evidence line) and what state the order is in on
their side. Trading ops: treat the order's state as unknown until confirmed. Do not resend it.

---

## STUCK_PARTIAL

**Symptom.** An order with CumQty > 0 that has no terminal state (Filled, Canceled, Expired,
Done for day) by the end of the log.

**Likely causes.**
1. Nothing is wrong: the order is still working and the log simply ends (GTC orders, or a log
   captured intraday).
2. The terminal report (final fill, expiry, done-for-day) was lost or never sent.
3. A cancel was requested and rejected, or never answered, so the remainder is still live when
   the client believes otherwise.
4. Session dropped before end of day and the end-of-day status reports were never received.

**Check next.**
- `fixscan order <ClOrdID>`: TimeInForce(59) on the order (Day orders must end the day terminal;
  GTC need not), time of last activity, `unansweredRequests`, any cancel reject.
- `fixscan stats`: when does the log end relative to market close? "No activity for the last N"
  in the finding tells you how long it sat.
- `fixscan session <session>`: gaps or silence after the last fill; did the session log out
  cleanly?

**Who to contact.** Counterparty: ask for the current status of the order (quote OrderID(37)).
Trading ops / middle office: the booked quantity should be CumQty; the remainder needs a
confirmed status before the position is treated as final. Client if a rejected cancel left them
exposed.

---

## OVERFILL

**Symptom.** CumQty(14) > OrderQty(38) on an ExecutionReport. CRITICAL: the position is larger
than ordered.

**Likely causes.**
1. A fill and a cancel or reduce-quantity replace crossed: the venue filled against the old
   quantity.
2. The counterparty double-reported a fill under a new ExecID (compare with DUPLICATE_EXEC,
   where the ExecID repeats).
3. The quantity was amended and the log is missing the accepted replace, so the engine's
   OrderQty is stale.
4. A genuine venue or broker error.

**Check next.**
- `fixscan order <ClOrdID>`: list every fill (LastQty, LastPx, ExecID, time). Are two fills
  identical in quantity, price and time? Was there a replace changing OrderQty, and was it
  accepted (ExecType=5) before the fills?
- Does OrderQty(38) on the fill report itself differ from the original order? The counterparty's
  own view of the quantity is on each ExecutionReport.
- `fixscan session <session>`: a gap just before could hide a replace acknowledgement (cause 3).

**Who to contact.** Counterparty immediately, with the ExecIDs: ask them to confirm each
execution and bust any that are erroneous. Trading desk and risk: the excess is real exposure
until resolved. Middle office: hold booking of the excess until confirmed.

---

## QTY_MISMATCH

**Symptom.** Either the sum of LastQty(32) over fills differs from CumQty(14), or LeavesQty(151)
differs from OrderQty(38) - CumQty(14) on an active order.

**Likely causes.**
1. A fill report is missing from the log (sum of fills < CumQty). Often paired with a SEQ_GAP.
2. The counterparty's cumulative counters are wrong (a real bug on their side).
3. A trade bust or correction was applied on their side but not reported, or reported in a way
   the log does not show (sum of fills > CumQty).
4. The order quantity was changed (replace, or unsolicited restatement) and LeavesQty reflects
   the new quantity.

**Check next.**
- `fixscan order <ClOrdID>`: walk the fills and add them up. At which report does the running
  total stop matching CumQty? `sumOfFills` vs `cumQty` in the order header gives the size of
  the difference.
- `fixscan session <session>`: is there a sequence gap between the last consistent report and
  the first inconsistent one? Check the MsgSeqNum values on the two reports: are they
  consecutive?
- If consecutive with no gap, nothing is missing from the log: the counterparty reported
  inconsistent numbers (cause 2).

**Who to contact.** Counterparty: ask for their execution list for the order and reconcile by
ExecID. Middle office: book only what is supported by individual execution reports until
reconciled. FIX connectivity team if a gap is involved.

---

## DUPLICATE_EXEC

**Symptom.** The same ExecID(17) on two messages, where the second is not marked
PossDupFlag(43)=Y or PossResend(97)=Y.

**Likely causes.**
1. Counterparty resent after a reconnect or failover without setting PossDup / PossResend.
2. Counterparty generates ExecIDs that are not unique (e.g. per-order counters, or reused after
   a restart).
3. The same message was captured twice by the logging pipeline (the log itself is duplicated).

**Check next.**
- Compare the two evidence lines. Same MsgSeqNum means the *log* is duplicated (cause 3), which
  is harmless. Different MsgSeqNum and otherwise identical content means a real resend
  (cause 1). Different content with the same ExecID means non-unique ids (cause 2).
- `fixscan session <session>`: a logon, resend request or gap just before the duplicate supports
  cause 1.
- `fixscan order <ClOrdID>`: does CumQty advance on the second message? If not, the counterparty
  itself treats it as the same execution.

**Who to contact.** Middle office / booking: make sure the execution was booked once (search
the booking system for the ExecID). Counterparty connectivity: report the missing PossDup flag
or non-unique ExecIDs.

---

## BROKEN_CHAIN

**Symptom.** A cancel, replace or execution report carries an OrigClOrdID(41) that never
appears as a ClOrdID(11) in the log.

**Likely causes.**
1. The log starts after the original order was sent (previous day's GTC, or log rotation).
2. The sender used the wrong id: a typo, an internal id instead of the FIX ClOrdID, or an id
   from before an accepted replace that was itself lost.
3. The original order went over a different session or file not included in the scan.

**Check next.**
- `fixscan stats`: when does the log start? Compare with the time of the request.
- `fixscan grep 11=<prefix>*` with the leading part of the unknown id: do similar ids exist?
  A near-miss suggests a typo or a different id scheme.
- `fixscan grep 37=<OrderID>` if any message in the chain carries an OrderID: it may link to
  the real order.
- What did the counterparty answer? A cancel reject with CxlRejReason=1 (Unknown order) means
  they do not know the id either (cause 2). An acceptance means they do, and only your log is
  incomplete (cause 1 or 3).

**Who to contact.** If the counterparty also rejected it: your OMS / FIX application team (wrong
id generated) and the desk (the order they meant to cancel is still live). If only the log is
incomplete: ask ops for the earlier log file.

---

## SEQ_GAP

**Symptom.** MsgSeqNum(34) skipped ahead in one direction (messages missing), or went backwards
without PossDupFlag. The finding says whether a ResendRequest was sent and whether the gap was
recovered. LOW if recovered, HIGH if not.

**Likely causes.**
1. Network interruption or a slow consumer: messages dropped, usually with a heartbeat silence
   just before.
2. One side restarted or failed over and resumed with a different sequence number.
3. Sequence numbers were reset on one side only (backwards case).
4. The *log* dropped lines even though the session was fine (logging pipeline loss).

**Check next.**
- `fixscan session <session>`: look at `seqGaps`, `heartbeatSilences`, `sequenceEvents` and
  `lifecycle` around the gap. Silence then gap means an outage. Logon then gap means a restart.
  A SequenceReset without GapFill means lost messages were deliberately not resent.
- Was the gap recovered? If a ResendRequest was sent and nothing came back, the missing
  messages are still unknown: they could include fills.
- `fixscan scan <files> --session <session> --since <gap time>`: breaks after the gap
  (NO_ACK, QTY_MISMATCH, STUCK_PARTIAL) are probably consequences of it.
- For cause 4: if no ResendRequest was sent and the session carried on normally, the receiving
  engine did not see a gap, so the loss is in the log, not the session.

**Who to contact.** FIX connectivity / network team for the cause. Counterparty connectivity:
ask what messages N to M were (quote the range) and for the unanswered ResendRequest to be
looked at. Trading ops: reconcile all orders active on that session during the gap against the
counterparty's records.

---

## INVALID_STATE_TRANSITION

**Symptom.** An ExecutionReport that the order's state should not allow: a fill after Filled,
Canceled, Rejected or Expired (CRITICAL); a terminal order going back to a live status; or
CumQty decreasing without a Trade Cancel / Trade Correct.

**Likely causes.**
1. A fill and a cancel crossed inside the counterparty's systems: the cancel was confirmed,
   then a fill that had already happened was reported late.
2. Out-of-order delivery after a failover or resend.
3. A trade bust or correction sent without the proper ExecType (H or G), showing up as CumQty
   going down.
4. Counterparty state machine bug.

**Check next.**
- `fixscan order <ClOrdID>`: compare TransactTime(60) and SendingTime(52) on the late fill with
  the cancel confirmation. If the fill's TransactTime is *before* the cancel, the trade
  happened first and was reported late (cause 1): the fill is probably real.
- Is the late message PossDup? Are MsgSeqNum values in order? (cause 2)
- `fixscan session <session>`: any gap, resend or logon between the two messages.
- Does CumQty on the late fill include it (e.g. 400 then 500)? The counterparty's own totals
  say whether they count the trade.

**Who to contact.** Counterparty immediately: ask whether the execution (quote the ExecID)
stands. Trading desk and risk: an order they consider dead may have traded. Middle office: do
not finalise the booking until confirmed.

---

## PARSE_ERROR

**Symptom.** Lines that look like FIX but have a wrong CheckSum(10) or BodyLength(9), or are
truncated or malformed. Grouped per kind and file.

**Likely causes.**
1. The log was edited, masked or exported by a tool that changed field contents without
   recomputing BodyLength and CheckSum (very common when many lines are affected).
2. The logger truncates long lines.
3. Multi-byte characters re-encoded by the logging pipeline.
4. Genuine corruption on the wire (rare; the receiving engine would have rejected it).

**Check next.**
- How many, out of how many messages (`fixscan stats`)? A handful is noise. Most of the file
  means cause 1 and can be mentioned once and set aside.
- Are the affected lines part of the order under investigation? Bad-checksum lines are still
  analysed; truncated lines are not applied to order state, so a truncated fill can cause a
  QTY_MISMATCH or STUCK_PARTIAL downstream.

**Who to contact.** Whoever supplied the log (ask for the raw, unedited file) or the team that
owns the logging pipeline.

---

## Combinations worth recognising

- **SEQ_GAP (unrecovered) + NO_ACK or QTY_MISMATCH on the same session shortly after:** the
  missing messages were probably the acknowledgements or fills. Root cause is the gap.
- **CANCEL_REPLACE_REJECTED + STUCK_PARTIAL on one chain:** the client tried to cancel, was
  refused, and the order then traded. The order is live and partly filled; the client may not
  realise.
- **CANCEL_REPLACE_REJECTED (Unknown order) + BROKEN_CHAIN:** the request pointed at an id
  neither side knows. The order the trader meant to cancel is untouched.
- **DUPLICATE_EXEC right after a Logon or ResendRequest:** resend without PossDup.
- **INVALID_STATE_TRANSITION (fill after cancel) + OVERFILL:** a cancel/fill race where the late
  fill also exceeded the quantity.
- **Many ORDER_REJECTED with the same OrdRejReason in a short window:** one upstream cause
  (limit, symbol set-up, market state), not many separate problems.
- **BUSINESS_REJECT (Application not available) cluster + heartbeats still flowing:** the FIX
  session is up but the application behind it is down.
