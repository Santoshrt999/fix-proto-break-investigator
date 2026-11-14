# FIX tags and values used in break investigations

A working subset of the public FIX 4.2 / 4.4 / 5.0 SP2 specifications. For anything not listed,
run `fixscan explain-tag <tag> [value]`. For venue or firm custom tags (usually 5000 and above),
ask for the counterparty's dictionary and pass it with `--dict`; do not guess.

## Contents

- [Message types (35)](#message-types-35)
- [Header and session tags](#header-and-session-tags)
- [Order identity and chains](#order-identity-and-chains)
- [Order terms](#order-terms)
- [Execution reports](#execution-reports)
- [Reject tags](#reject-tags)
- [How quantities must add up](#how-quantities-must-add-up)
- [Order state model](#order-state-model)
- [Session recovery in brief](#session-recovery-in-brief)
- [Masked tags](#masked-tags)

## Message types (35)

| 35 | Name | Level | Notes |
| --- | --- | --- | --- |
| 0 | Heartbeat | Session | Sent when idle for HeartBtInt(108) seconds; echoes TestReqID(112) when answering a TestRequest |
| 1 | TestRequest | Session | "Are you there?" Must be answered by a Heartbeat with the same 112 |
| 2 | ResendRequest | Session | Asks for BeginSeqNo(7) to EndSeqNo(16); 16=0 means "to latest" |
| 3 | Reject | Session | Message violated the FIX dictionary; see 45, 371, 372, 373 |
| 4 | SequenceReset | Session | 123=Y: gap fill (skips admin messages during a resend). Without 123=Y: hard reset, lost messages are not resent |
| 5 | Logout | Session | Text(58) often says why |
| A | Logon | Session | 108 = heartbeat interval; 141=Y resets sequence numbers to 1 |
| D | NewOrderSingle | Application | New order |
| F | OrderCancelRequest | Application | 11 = new id for the request, 41 = the order to cancel |
| G | OrderCancelReplaceRequest | Application | Amend. 11 = new id, 41 = the order being replaced |
| 8 | ExecutionReport | Application | Every order state change and fill |
| 9 | OrderCancelReject | Application | A cancel or replace was refused; see 102, 434 |
| j | BusinessMessageReject | Application | Valid FIX, but the application would not process it; see 380 |

## Header and session tags

| Tag | Name | Notes |
| --- | --- | --- |
| 8 | BeginString | FIX.4.2, FIX.4.4, or FIXT.1.1 (FIX 5.0+) |
| 9 | BodyLength | Byte count; validated by fixscan |
| 10 | CheckSum | Sum of bytes mod 256; validated by fixscan |
| 34 | MsgSeqNum | Increases by 1 per message, separately in each direction |
| 43 | PossDupFlag | Y = resend of a sequence number already sent |
| 49 / 56 | SenderCompID / TargetCompID | Identify the session and the direction |
| 52 | SendingTime | UTC. `yyyyMMdd-HH:mm:ss[.sss]` |
| 97 | PossResend | Y = application-level resend under a new sequence number |
| 122 | OrigSendingTime | On PossDup resends: when it was first sent |
| 36 | NewSeqNo | On SequenceReset: the next sequence number to expect |
| 45 | RefSeqNum | On rejects: MsgSeqNum of the message being rejected |
| 108 | HeartBtInt | Seconds |
| 112 | TestReqID | Links a TestRequest to the Heartbeat that answers it |
| 123 | GapFillFlag | Y on SequenceReset = gap fill |
| 141 | ResetSeqNumFlag | Y on Logon = both sides restart at 1 |

## Order identity and chains

| Tag | Name | Notes |
| --- | --- | --- |
| 11 | ClOrdID | Assigned by the order sender. **Every cancel and replace request gets a new one** |
| 41 | OrigClOrdID | The ClOrdID of the order being cancelled or replaced: always the latest *accepted* one |
| 37 | OrderID | Assigned by the counterparty; constant for the life of the order. "NONE" on rejects |
| 17 | ExecID | Unique per ExecutionReport from that counterparty |
| 19 | ExecRefID | On trade cancel / correct: the ExecID being busted or corrected |

A chain looks like: `D 11=A` then `G 11=B 41=A` then `G 11=C 41=B` then `F 11=D 41=C`.
ExecutionReports for a pending or accepted request carry both 11 (new) and 41 (previous). After
a replace is accepted (150=5), fills come back under the new ClOrdID. After a replace or cancel
is *rejected*, the order continues under the previous ClOrdID.

## Order terms

| Tag | Name | Values |
| --- | --- | --- |
| 1 | Account | Masked |
| 38 | OrderQty | |
| 40 | OrdType | 1 Market, 2 Limit, 3 Stop, 4 Stop limit, 5 Market on close, B Limit on close, P Pegged |
| 44 | Price | Required for limit orders |
| 54 | Side | 1 Buy, 2 Sell, 5 Sell short, 6 Sell short exempt, 8 Cross |
| 55 | Symbol | |
| 59 | TimeInForce | 0 Day, 1 GTC, 2 At the opening, 3 IOC, 4 FOK, 6 GTD, 7 At the close |
| 60 | TransactTime | When the business event happened (vs 52, when the message was sent) |
| 21 | HandlInst | 1 Automated private, 2 Automated public, 3 Manual |
| 100 | ExDestination | Target venue |

## Execution reports

**ExecType (150): what this report is about.**

| 150 | Meaning |
| --- | --- |
| 0 | New (acknowledged) |
| F | Trade (partial fill or fill). FIX 4.3 and later |
| 1 / 2 | Partial fill / Fill. FIX 4.2 only |
| 4 | Canceled |
| 5 | Replaced (amend accepted) |
| 6 | Pending Cancel |
| E | Pending Replace |
| A | Pending New |
| 8 | Rejected |
| C | Expired |
| 3 | Done for day |
| D | Restated (unsolicited change by the counterparty; see ExecRestatementReason 378) |
| H | Trade Cancel (bust) |
| G | Trade Correct |
| I | Order Status (reply to a status request; not a new event) |

**OrdStatus (39): the order's state after this report.**

| 39 | Meaning | Terminal? |
| --- | --- | --- |
| A | Pending New | no |
| 0 | New | no |
| 1 | Partially filled | no |
| 2 | Filled | yes |
| 3 | Done for day | yes |
| 4 | Canceled | yes |
| 5 | Replaced | no (FIX 4.2 status; later versions report the live status instead) |
| 6 | Pending Cancel | no |
| E | Pending Replace | no |
| 8 | Rejected | yes |
| C | Expired | yes |
| 7 / 9 | Stopped / Suspended | no |

FIX 4.2 also has ExecTransType (20): 0 New, 1 Cancel (bust), 2 Correct, 3 Status.

**Quantities and prices.**

| Tag | Name | Notes |
| --- | --- | --- |
| 32 | LastQty | Quantity of this fill (LastShares in 4.2) |
| 31 | LastPx | Price of this fill |
| 14 | CumQty | Total filled so far |
| 151 | LeavesQty | Still open. 0 once the order is terminal |
| 6 | AvgPx | Average price of all fills so far |

## Reject tags

| Tag | Name | On | Key values |
| --- | --- | --- | --- |
| 103 | OrdRejReason | 35=8 with 39=8 | 0 Broker option, 1 Unknown symbol, 2 Exchange closed, 3 Order exceeds limit, 4 Too late to enter, 5 Unknown order, 6 Duplicate order, 8 Stale order, 11 Unsupported order characteristic, 13 Incorrect quantity, 15 Unknown account, 99 Other |
| 102 | CxlRejReason | 35=9 | 0 Too late to cancel, 1 Unknown order, 2 Broker option, 3 Already pending cancel/replace, 6 Duplicate ClOrdID, 99 Other |
| 434 | CxlRejResponseTo | 35=9 | 1 Cancel request, 2 Cancel/replace request |
| 373 | SessionRejectReason | 35=3 | 0 Invalid tag number, 1 Required tag missing, 2 Tag not defined for this message, 3 Undefined tag, 4 Tag without value, 5 Value incorrect, 6 Incorrect data format, 9 CompID problem, 10 SendingTime accuracy, 11 Invalid MsgType, 13 Tag repeated, 14 Out of order, 15/16 Repeating group errors, 99 Other |
| 371 | RefTagID | 35=3 | The tag number that caused the reject |
| 372 | RefMsgType | 35=3, 35=j | MsgType of the rejected message |
| 380 | BusinessRejectReason | 35=j | 0 Other, 1 Unknown ID, 2 Unknown Security, 3 Unsupported Message Type, 4 Application not available, 5 Conditionally required field missing, 6 Not authorized |
| 379 | BusinessRejectRefID | 35=j | Usually the ClOrdID of the rejected message |
| 58 | Text | any | Free text. Often the most specific reason. Partly masked |

## How quantities must add up

On every ExecutionReport for an active order:

- `CumQty(14)` = sum of `LastQty(32)` over all trade reports so far (minus busted trades)
- `LeavesQty(151)` = `OrderQty(38)` - `CumQty(14)`
- `CumQty(14)` <= `OrderQty(38)`
- `CumQty` never decreases, except through a Trade Cancel (150=H) or Trade Correct (150=G)

Once the order is Canceled, Expired, Rejected or Done for day, `LeavesQty` is 0 even though
`CumQty` < `OrderQty`.

## Order state model

```
            +--> Rejected (8)
            |
NewOrderSingle --> [Pending New (A)] --> New (0) --> Partially filled (1) --> Filled (2)
                                           |                |
                                           |                +--> Canceled (4) / Expired (C) / Done for day (3)
                                           +--> Canceled (4) / Expired (C) / Done for day (3)

Cancel request:   live --> Pending Cancel (6) --> Canceled (4)      or  OrderCancelReject (order unchanged)
Replace request:  live --> Pending Replace (E) --> Replaced (150=5) or  OrderCancelReject (order unchanged)
```

Filled, Canceled, Rejected, Expired and Done for day are terminal. A fill reported after a
terminal state, or a return from a terminal state to a live one, is not allowed by the model.

## Session recovery in brief

1. Receiver expects MsgSeqNum N but gets N+k: a gap of k messages.
2. Receiver sends ResendRequest (35=2) with 7=N, 16=0 (or the last missing number).
3. Sender resends application messages with PossDupFlag(43)=Y and their original sequence
   numbers, and replaces admin messages (heartbeats and so on) with SequenceReset-GapFill
   (35=4, 123=Y, 36=next number).
4. Once everything up to the current number is covered, the gap is recovered.

Signs of trouble: a ResendRequest with no reply; a SequenceReset *without* 123=Y (the sender is
declaring the missing messages gone); MsgSeqNum lower than expected without 43=Y (one side
reset or restarted); no message at all for more than about twice HeartBtInt.

## Masked tags

fixscan masks these by default; they show as `***`:

- Account (1), ClientID (109), PartyID (448)
- Inside Text (58): account-like tokens, email addresses, and names following words such as
  "account", "client", "trader", "user"
- Any extra tags given with `--mask-tags`

Order identifiers (11, 41, 37, 17), symbols, quantities, prices and reject codes are not masked.
