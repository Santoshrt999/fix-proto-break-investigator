# Worked example

A complete run of the investigation loop on the bundled synthetic log `samples/day1.log`. Use it
as a model for depth and tone; the data is synthetic.

**User:** "Client says they cancelled ORD-000211 in ACME but got filled anyway. What happened?"

## 1. Scope

The user named the order and the file is known. No questions needed.

```
fixscan stats samples/day1.log
```

Time range 09:30:00 to 10:15:00.210 UTC, sessions BUYSIDE1-BROKERX and BUYSIDE1-BROKERY,
1072 messages, 2 parse errors (1 bad checksum, 1 truncated). The log covers the day and ends
with logouts, so "nothing after" is meaningful. BUYSIDE1 sends the orders: this is the buy
side's log.

## 2. Triage

```
fixscan order ORD-000211 samples/day1.log
```

One match. Chain `ORD-000211 > ORD-000212 > ORD-000213 > ORD-000214`, OrderID BXO0000104,
Sell 1500 ACME @ 48.4, status `1 (Partially filled)`, cumQty 600, leavesQty 900, sumOfFills 600.
Two findings on it: CANCEL_REPLACE_REJECTED-3 and STUCK_PARTIAL-2.

```
fixscan scan samples/day1.log --session BUYSIDE1-BROKERX --since 2026-01-05T10:00:00 --until 2026-01-05T10:15:00
```

Shows the same two findings plus unrelated ones on other orders (BROKEN_CHAIN and
CANCEL_REPLACE_REJECTED on ORD-000181, INVALID_STATE_TRANSITION on ORD-000195). Set those aside and mention them at the end.

## 3. Hypothesize

From the playbook (CANCEL_REPLACE_REJECTED, STUCK_PARTIAL, and the combination "cancel rejected
+ stuck partial"):

1. The cancel was rejected and the order stayed live, then traded. (Fits the findings.)
2. The cancel referenced a stale ClOrdID (not the latest accepted one), so it was rejected as
   unknown. Check OrigClOrdID(41) on the cancel against the latest accepted replace.
3. The fill happened before the cancel arrived (a race), and the reject is just reporting that.
   Check the order of TransactTime on the fill versus the cancel.
4. The session was unhealthy (gap, silence) and messages were lost. Check `session`.

## 4. Verify

From the `order` timeline:

| Time (UTC) | Line | From | Message |
| --- | --- | --- | --- |
| 10:07:00.000 | day1.log:870 | BUYSIDE1 | NewOrderSingle ORD-000211 Sell 1000 ACME Limit 48.30 Day |
| 10:07:00.020 | day1.log:871 | BROKERX | ExecutionReport New, OrderID BXO0000104 |
| 10:07:03.000 | day1.log:873 | BUYSIDE1 | Replace ORD-000212 (41=ORD-000211): qty 1000 to 1500 |
| 10:07:03.029 | day1.log:875 | BROKERX | Replaced, OrderQty 1500 |
| 10:07:07.029 | day1.log:879 | BUYSIDE1 | Replace ORD-000213 (41=ORD-000212): price 48.30 to 48.40 |
| 10:07:07.054 | day1.log:881 | BROKERX | Replaced, Price 48.40 |
| 10:07:12.054 | day1.log:882 | BUYSIDE1 | OrderCancelRequest ORD-000214 (41=ORD-000213) |
| 10:07:12.066 | day1.log:883 | BROKERX | OrderCancelReject: CxlRejReason 0 (Too late to cancel), OrdStatus 0 (New), Text "Too late to cancel: order is in closing auction" |
| 10:07:12.504 | day1.log:884 | BROKERX | Fill 600 @ 48.40, CumQty 600, LeavesQty 900, OrdStatus Partially filled |

- Hypothesis 2, **ruled out**: the cancel's OrigClOrdID is ORD-000213, the latest accepted
  replace (day1.log:881), and the reason is 0 (Too late), not 1 (Unknown order).
- Hypothesis 3, **ruled out**: the reject reports the order as New (OrdStatus 0, day1.log:883),
  and the last report before it shows CumQty 0 (day1.log:881); the fill came 438 ms later
  (day1.log:884). Nothing had traded when the cancel
  was refused.
- Hypothesis 4, **ruled out**: `fixscan session BUYSIDE1-BROKERX` shows health DEGRADED only
  because of one recovered gap at 09:45 (day1.log:353), long before this order. No silence or
  gap around 10:07; MsgSeqNum 389 then 390 on lines 883 and 884 are consecutive.
- Hypothesis 1, **confirmed**: the reject text gives the reason (closing auction), the order
  stayed live, and it then traded 600. No later message for the chain up to the logouts at
  10:15 (STUCK_PARTIAL-2: no activity for the last 7m 48s).
- Isolated or systemic: `fixscan grep 35=9 samples/day1.log` shows 3 cancel rejects; only this
  one mentions the closing auction.

## 5. Report

```
## Root cause
The cancel for ORD-000211 (sent as ORD-000214) was rejected by BROKERX as too late because the
order was in the closing auction. The order stayed live and was then partially filled 600 of
1500 at 48.40. It was not cancelled.

## Confidence: high
The reject states the reason directly, and the order's status before and after it is in the log.

## Timeline (UTC)
(the table above)

## Evidence
Facts (from the logs):
- The cancel request referenced the latest accepted ClOrdID ORD-000213 — day1.log:882, day1.log:881
- BROKERX rejected it: CxlRejReason(102)=0 Too late to cancel, Text "Too late to cancel: order is
  in closing auction", order status New — day1.log:883
- 438 ms later BROKERX reported a fill of 600 @ 48.40, CumQty 600, LeavesQty 900 — day1.log:884
- No further message for this order after the fill — day1.log:884 is the last line of the chain;
  the BUYSIDE1-BROKERX session logs out at day1.log:1069
Inference (my reading, not proven by the logs):
- The client may believe the order was cancelled; the remaining 900 could still be working or
  expired at the broker's end of day. Confirm with BROKERX.

## Ruled out
- Stale OrigClOrdID: the cancel pointed at the live id ORD-000213 (day1.log:881, day1.log:882).
- Fill before cancel: status was New when the cancel was refused (day1.log:883) and CumQty was
  still 0 (day1.log:881).
- Session problem: no gap or silence near 10:07; seq 389 and 390 are consecutive.

## Suggested next steps
- BROKERX support: ask for the final status of OrderID BXO0000104 (remaining 900) and whether
  any further executions happened after 10:07:12.504.
- Trading desk / client: tell them the cancel was refused and 600 ACME were sold at 48.40.
- Middle office: book the 600 (ExecID BXE0000356); hold the remainder until BROKERX confirms.
- Desk / OMS owners: cancels sent during the closing auction are refused by this broker;
  consider surfacing CxlRejReason in the client's blotter.

## Also seen
- BROKEN_CHAIN + CANCEL_REPLACE_REJECTED on ORD-000181: cancel referenced ORD-900001, which
  neither the log nor BROKERX knows (day1.log:739, day1.log:740).
- INVALID_STATE_TRANSITION on ORD-000195: fill reported after the order was Canceled
  (day1.log:811).
```
