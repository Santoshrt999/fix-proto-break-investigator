# fixscan output reference

What each command returns and how to read it. Every response is one JSON object with
`command` and `masking` (`"on"` unless `--no-mask` was given). Errors are
`{"error": "..."}` with exit code 2. Times are ISO-8601 UTC. `at` is always `file:line` using
the file's base name (for example `day1.log:82`): cite it exactly as printed.

## Contents

- [Severity by rule](#severity-by-rule)
- [stats](#stats)
- [scan](#scan)
- [Finding](#finding)
- [order](#order)
- [session](#session)
- [grep](#grep)
- [explain-tag](#explain-tag)
- [Custom dictionary (--dict)](#custom-dictionary---dict)

## Severity by rule

Severity says how dangerous a break is, not how relevant it is to the question. A MEDIUM
ORDER_REJECTED on the order the user asked about matters more than an unrelated CRITICAL.

| Rule | Severity | Fires when |
| --- | --- | --- |
| OVERFILL | CRITICAL | CumQty(14) > OrderQty(38) |
| INVALID_STATE_TRANSITION | CRITICAL for a fill after a terminal state; HIGH for a terminal order returning to a live status, or CumQty going down without a Trade Cancel/Correct | |
| SESSION_REJECT | HIGH | Reject (35=3) received |
| BUSINESS_REJECT | HIGH | BusinessMessageReject (35=j) received |
| DUPLICATE_EXEC | HIGH | ExecID(17) repeated without PossDupFlag(43)=Y or PossResend(97)=Y |
| QTY_MISMATCH | HIGH | sum of LastQty(32) != CumQty(14), or LeavesQty(151) != OrderQty - CumQty on a live order |
| NO_ACK | HIGH if no ExecutionReport ever arrives; MEDIUM if it arrives after the timeout | NewOrderSingle unanswered for `--ack-timeout` seconds (default 30) |
| SEQ_GAP | HIGH if unrecovered or MsgSeqNum went backwards; LOW if recovered | MsgSeqNum(34) jumped or went back |
| ORDER_REJECTED | MEDIUM | ExecType(150)=8 or OrdStatus(39)=8 |
| CANCEL_REPLACE_REJECTED | MEDIUM | OrderCancelReject (35=9) received |
| STUCK_PARTIAL | MEDIUM | CumQty > 0 and no terminal state by end of log |
| BROKEN_CHAIN | MEDIUM | OrigClOrdID(41) refers to a ClOrdID never seen in the log |
| PARSE_ERROR | LOW | bad CheckSum(10), bad BodyLength(9), truncated or malformed lines (grouped) |

Heartbeat silences and unanswered TestRequests are not findings on their own; they show up in
`session` and in `scan`'s `summary.sessions[].heartbeatGaps`.

## stats

| Field | Meaning |
| --- | --- |
| `files[]` | `file`, `bytes`, `lines` per input file |
| `messages` | FIX messages recognised |
| `nonFixLines` | Lines that did not contain a FIX message (log noise, or an unrecognised format) |
| `timeRange.first` / `.last` | First and last message time |
| `parseErrors` | `badCheckSum`, `badBodyLength`, `truncated`, `malformed` counts |
| `byMsgType` | Message counts, e.g. `"ExecutionReport (8)": 609` |
| `fixVersions` | BeginString values seen |
| `sessions[]` | `name` (`SENDER-TARGET` of the side that initiated), `beginString`, `messages`, `first`, `last`, `logons`, `logouts` |

Use the session `name` exactly as printed in `--session` and in `fixscan session`.

## scan

| Field | Meaning |
| --- | --- |
| `summary.messages`, `summary.orders` | Totals |
| `summary.timeRange` | First and last message time |
| `summary.sessions[]` | `name`, `messages`, `seqGaps`, `unrecoveredSeqGaps`, `heartbeatGaps` |
| `summary.parseErrors` | Total parse problems |
| `summary.findings` | Total findings **after** filters |
| `summary.bySeverity`, `summary.byRule` | Counts after filters |
| `findings[]` | Most severe first, then by time; at most `--limit` (default 50) |
| `omitted` | How many findings were not shown. Narrow the scan rather than raise `--limit` |
| `hint` | What to do next when output was cut |

## Finding

| Field | Meaning |
| --- | --- |
| `id` | `RULE-n`, unique within one run (do not rely on it across runs) |
| `ruleId`, `severity` | See the table above |
| `session` | `SENDER-TARGET`, or null (parse errors) |
| `clOrdIds` | The order's amend/cancel chain, oldest first; empty for session findings. Use any of them with `fixscan order` |
| `firstTime` | When the story started (usually the NewOrderSingle) |
| `time` | When the break was detected |
| `summary` | One line with the key values, already decoded |
| `evidence[]` | Up to 5 lines: `at` (file:line) and `excerpt` (masked `tag=value|...`). Inside `order` output, evidence is just the list of `file:line` |

## order

Accepts a ClOrdID (any id in the chain) or an OrderID(37).

| Field | Meaning |
| --- | --- |
| `matches` | Number of orders found; 0 comes with a `hint` |
| `orders[].session` | Session the order was on |
| `clOrdIds` | Whole chain, oldest first; `currentClOrdId` is the live one |
| `orderId` | Counterparty's OrderID, null if never assigned (e.g. rejected with 37=NONE) |
| `terms` | e.g. `Buy 500 ACME @ 48.25` (latest accepted terms) |
| `status` | Latest OrdStatus, decoded, or `no ExecutionReport seen` |
| `orderQty`, `cumQty`, `leavesQty`, `avgPx` | Latest values as reported by the counterparty |
| `sumOfFills` | Sum of LastQty over trade reports. Should equal `cumQty` |
| `newOrderSeen` | false: the NewOrderSingle is not in these files |
| `note` | Explains a missing NewOrderSingle or a placeholder order (only referenced as OrigClOrdID) |
| `rejectedBeforeAck` | true if the first response was a reject |
| `ackLatencyMs` | Time from NewOrderSingle to first ExecutionReport |
| `unansweredRequests[]` | Cancel/replace requests with no response: `clOrdId`, `type`, `at`, `time` |
| `timeline[]` | Every message of the chain, oldest first: `at`, `time`, `from`, `msgType`, then key fields decoded (`ExecType`, `OrdStatus`, `LastQty`, `CumQty`, `OrdRejReason`, `Text`...) |
| `timelineOmitted`, `timelineNote` | Present when the timeline exceeded `--limit` (default 200): the first and last entries are kept |
| `findings[]` | Findings on this order |

## session

Argument: `SENDER-TARGET` as printed by `stats` (either order of the two CompIDs is accepted
when unambiguous; if not found, `availableSessions` lists the real names).

| Field | Meaning |
| --- | --- |
| `health` | `OK`; `DEGRADED` (recovered gaps, silences, unanswered TestRequests, hard resets); `BROKEN` (unrecovered gap or MsgSeqNum went backwards) |
| `problems[]` | Plain-language list of what makes it DEGRADED or BROKEN |
| `heartBtIntSeconds` | HeartBtInt(108) from the Logon |
| `logons`, `logouts`, `loggedOnAtEnd` | Session lifecycle counts; `lifecycle[]` lists each Logon/Logout with `from`, `at`, `time`, `text` |
| `directions[]` | One entry per direction (`A->B`): `messages`, `firstSeq`, `lastSeq`, `heartbeats`, `testRequests`, `resendRequestsSent`, `possDupResends`, `gapFills`, `sequenceResets` |
| `directions[].seqGaps[]` | `expected`, `received`, `missing`, `resendRequestAt`, `recovered`, `recoveredBy`, `at`, `time`; or `kind: "sequence went backwards"` |
| `directions[].heartbeatSilences[]` | `silentFrom`, `silentUntil`, `seconds`, `lastMessageAt`, `resumedAt`: no message from that sender for well over HeartBtInt |
| `directions[].unansweredTestRequests[]` | `testReqId`, `at`, `time` |
| `directions[].sequenceEvents[]` | ResendRequest (`beginSeqNo`, `endSeqNo`), SequenceReset (`newSeqNo`, GapFill or hard reset) with `at`, `time` |
| `findingsOnThisSession` | Finding counts by rule for this session |

Read a gap together with what happened just before it: a silence then a gap means an outage; a
Logon then a gap means a restart; a ResendRequest with no resends after it means the missing
messages were never recovered (they may have been acks or fills).

## grep

Query: `tag=value` terms separated by commas, all of which must match. `value` may contain `*`
(quote the query in the shell). A bare tag (`103`) matches when the tag is present. Tag names
from the dictionary work in place of numbers (`ClOrdID=ORD-1`).

| Field | Meaning |
| --- | --- |
| `matches` | Total matching messages |
| `shown` | Messages returned (default `--limit` 20) |
| `messages[]` | `at`, `time`, `msgType`, and `fields`: every field decoded as `Name(tag)=value (meaning)`, masked |
| `parseIssue` | On a message that also had a parse problem |
| `hint` | How to narrow when not everything was shown |

Use `matches` to answer "is it one or many?" without listing them all (`--limit 1`).

## explain-tag

`fixscan explain-tag 103` returns `name` and all `values`; `fixscan explain-tag 103 3` returns
the `meaning` of one value. Names work too (`explain-tag OrdStatus 8`). Unknown tags return
`known: false` with a hint to supply `--dict`.

## Custom dictionary (--dict)

A JSON file in the same shape as the built-in dictionary. Entries override or extend it:

```json
{
  "tags": {
    "5001": {"name": "VenueRiskCheckId"},
    "5002": {"name": "VenueRejectCode", "values": {"R1": "Credit limit", "R2": "Restricted list"}}
  }
}
```

Ask the user for the counterparty's dictionary or rules of engagement to build this; never
invent meanings for custom tags.
