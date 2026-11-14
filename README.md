# fix-break-investigator

**Ask Claude why a trade broke, and get a root cause with log-line evidence, from FIX logs that never leave your machine in bulk.**

A Claude Code plugin for engineers and support teams at banks, brokers and trading firms. It
works out why an order was rejected, never acknowledged, stuck partially filled, overfilled,
hit a sequence gap or does not match what was booked.

It has two layers:

1. **`fixscan`**, a deterministic Java engine (no AI, zero runtime dependencies). It streams
   huge FIX logs, correlates every message per order and per session, detects breaks, and prints
   small, structured JSON.
2. **The `fix-break-investigator` skill**, which turns Claude into an investigator. It calls
   `fixscan`, forms hypotheses, runs targeted queries to confirm or rule them out, and writes a
   report with a root cause, timeline, `file:line` evidence, confidence and who to contact.

The engine filters; the model reasons. Raw logs are never sent to the model in bulk, and client
identifiers are masked by default.

## 60-second quickstart

Needs a JDK 21+ (the first run builds the engine with the bundled Maven wrapper, which needs
network access to Maven Central once).

```sh
git clone https://github.com/Santoshrt999/fix-proto-break-investigator.git
cd fix-proto-break-investigator

bin/fixscan stats samples/day1.log --format text        # what is in the log
bin/fixscan scan samples/day1.log --format text         # all planted breaks, most severe first
bin/fixscan order ORD-000018 samples/day1.log --format text   # one order's full lifecycle
```

Then, in Claude Code with the plugin installed (below):

> Why was order ORD-000018 rejected? The log is samples/day1.log

## Install

As a Claude Code plugin (recommended):

```
/plugin marketplace add Santoshrt999/fix-proto-break-investigator
/plugin install fix-break-investigator@fix-tools
```

The plugin puts `fixscan` on Claude's PATH. The engine jar is built the first time it runs.

From a local clone (for development):

```sh
claude plugin marketplace add /path/to/fix-proto-break-investigator
claude plugin install fix-break-investigator@fix-tools
```

Manual install (skill only, no plugin system):

```sh
cd fix-proto-break-investigator
(cd engine && ./mvnw -DskipTests package)          # builds engine/target/fixscan.jar
mkdir -p ~/.claude/skills ~/.local/bin
cp -R skills/fix-break-investigator ~/.claude/skills/
ln -s "$PWD/bin/fixscan" ~/.local/bin/fixscan       # make sure ~/.local/bin is on your PATH
```

Keep the clone where it is: the `fixscan` link points into it.

## Example investigation

A real run in Claude Code with the plugin installed, on the bundled sample (report lightly
trimmed):

```text
> Why was order ORD-000018 rejected? The log is samples/day1.log

● Skill(fix-break-investigator)
● Read(references/fixscan-output.md)
● Bash(fixscan stats samples/day1.log)
● Bash(fixscan order ORD-000018 samples/day1.log)
● Bash(fixscan grep 35=8,103=3 samples/day1.log --limit 20 && fixscan scan samples/day1.log --limit 10)

## Root cause
ORD-000018 (Buy 50,000 UMBRELLA @ 310) was rejected by BROKERX because its notional,
50,000 × 310 = 15,500,000, exceeded the account's maximum of 10,000,000. The reject text states
this directly, and the arithmetic matches it.

## Confidence: high
The reject reason and text state the cause, and the notional calculated from the order's own
fields matches the figure BROKERX quoted. Only one order in the log carries this reject.

## Timeline (UTC)
| Time         | Line         | From     | Message                          | Detail                                          |
| 09:34:00.000 | day1.log:81  | BUYSIDE1 | NewOrderSingle (D), MsgSeqNum 15 | ORD-000018, Buy 50000 UMBRELLA, Limit 310.00     |
| 09:34:00.018 | day1.log:82  | BROKERX  | ExecutionReport (8), MsgSeqNum 31| Rejected, OrderID NONE, OrdRejReason(103)=3      |

## Evidence
Facts (from the logs):
- OrderQty(38)=50000, Price(44)=310.00 — day1.log:81
- OrdRejReason(103)=3 (Order exceeds limit), Text(58) "Order exceeds limit: notional
  15500000.00 > max 10000000.00 for account ***" — day1.log:82
- 50,000 × 310.00 = 15,500,000.00, matching the notional BROKERX quoted
Inference (not proven by the logs):
- Whether the 10m limit is per order, per day or cumulative needs BROKERX's risk configuration.

## Ruled out
- Session problem: no unrecovered gaps or heartbeat silences on BUYSIDE1-BROKERX; the reject
  arrived in sequence (MsgSeqNum 31).
- Field or format error: this is a business reject, not a session Reject (35=3).

## Suggested next steps
- BROKERX trading ops / risk: confirm the notional limit for the account behind ***; send
  ClOrdID ORD-000018, ExecID BXE0000027, day1.log:82.
- Buy-side trading desk: confirm whether a 15.5m order was intended.

## Also seen
- OVERFILL on ORD-000131 (CRITICAL), INVALID_STATE_TRANSITION on ORD-000195 (CRITICAL),
  unrecovered sequence gap on BROKERY→BUYSIDE1 (HIGH), and more. Not examined further.
```

## Commands

All commands print JSON by default; add `--format text` for humans. Files may be plain or
gzip; SOH, `|` and `^A` delimiters are recognised, with or without a leading timestamp or
direction prefix (for example QuickFIX/J-style log lines).

| Command | What it does |
| --- | --- |
| `fixscan stats <files...>` | Message counts by type, sessions, time range, parse errors |
| `fixscan scan <files...> [--since T --until T --session A-B --severity S --rule ID --limit 50]` | Summary counts by rule plus the most severe findings, each with evidence |
| `fixscan order <ClOrdID or OrderID> <files...>` | Full lifecycle timeline of one order including its amend/cancel chain |
| `fixscan session <SenderCompID-TargetCompID> <files...>` | Sequence and heartbeat health of one session |
| `fixscan grep <tag=value>[,tag=value] <files...> [--limit N]` | Matching messages, decoded and masked (`*` wildcards; quote them) |
| `fixscan explain-tag <tag> [value]` | Dictionary meaning of a tag or enum value |
| `fixscan rules` | The break detectors |
| `fixscan generate` | Regenerate the synthetic sample log |

Options: `--ack-timeout SECS` (NO_ACK threshold, default 30), `--dict FILE` (custom tags, same
JSON shape as the built-in dictionary), `--mask-tags 50,57`, `--no-mask`.
`FIXSCAN_JAVA_OPTS` passes JVM options (for example `-Xmx6g` for multi-gigabyte logs).

Every finding has an id, rule, severity, session, ClOrdID chain, timestamps, a one-line summary,
and up to five evidence lines (`file:line` plus the masked message).

## Supported break types

| Rule | Severity | What it detects |
| --- | --- | --- |
| `ORDER_REJECTED` | MEDIUM | ExecutionReport with ExecType/OrdStatus=8, with OrdRejReason(103) and Text(58) |
| `SESSION_REJECT` | HIGH | Reject (35=3) with RefTagID(371), RefMsgType(372), SessionRejectReason(373) |
| `BUSINESS_REJECT` | HIGH | BusinessMessageReject (35=j) |
| `CANCEL_REPLACE_REJECTED` | MEDIUM | OrderCancelReject (35=9) |
| `NO_ACK` | HIGH / MEDIUM | NewOrderSingle with no ExecutionReport within the timeout (never / late) |
| `STUCK_PARTIAL` | MEDIUM | Partially filled order with no terminal state by end of log |
| `OVERFILL` | CRITICAL | CumQty > OrderQty |
| `QTY_MISMATCH` | HIGH | Sum of LastQty(32) != CumQty, or LeavesQty != OrderQty - CumQty |
| `DUPLICATE_EXEC` | HIGH | Repeated ExecID that is not PossDup / PossResend |
| `BROKEN_CHAIN` | MEDIUM | OrigClOrdID referencing an unknown ClOrdID |
| `SEQ_GAP` | HIGH / LOW | MsgSeqNum gap (unrecovered / recovered via ResendRequest, resends or GapFill), or seq going backwards |
| `INVALID_STATE_TRANSITION` | CRITICAL / HIGH | Fill after Filled/Canceled, terminal order revived, CumQty decreasing |
| `PARSE_ERROR` | LOW | Bad CheckSum(10) or BodyLength(9), truncated or malformed lines (reported, never fatal) |

`samples/day1.log` contains at least one planted instance of every rule, including an amend
chain whose final cancel is rejected before the order is partially filled.
`samples/expected-findings.json` lists them; the golden test checks the scan matches exactly.

## Privacy and masking

- **Masking is on by default.** Account(1), ClientID(109) and PartyID(448) are replaced with
  `***`. In Text(58), account-like tokens, email addresses and names or identifiers after cue
  words (account, client, trader, user...) are masked; the rest of the reject reason stays
  readable. Add tags with `--mask-tags`.
- `--no-mask` turns masking off and prints a warning to stderr. The skill never uses it unless
  you ask and confirm you may see that data.
- **Read-only.** fixscan never writes to logs and never connects to anything. The skill
  diagnoses and suggests who to contact; it never replays, resends or amends trades.
- The skill never reads whole log files: only fixscan's filtered, capped output reaches the
  model.
- This repository uses only public FIX specifications and synthetic data. Firm names in the
  samples (BUYSIDE1, BROKERX, BROKERY) are fictitious.

## Performance

The engine streams line by line and keeps compact per-order and per-session state. The
performance smoke test scans about 550k messages (136 MB) in under 2 seconds on a laptop; a
1 GB log takes seconds to tens of seconds depending on the machine.

## Roadmap

- **Phase 1 (this release):** FIX log engine, 12 break detectors, investigator skill.
- **Phase 2:** protobuf / JSON booking events through a pluggable `EventSource`.
- **Phase 3:** FIX-versus-booking reconciliation.
- **Phase 4:** Splunk / ELK connectors.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: `cd engine && ./mvnw verify`.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
