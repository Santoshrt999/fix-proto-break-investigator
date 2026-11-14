---
name: fix-break-investigator
description: Investigates why a trade or order broke by analysing FIX protocol logs with the read-only fixscan engine, then reports a root cause backed by file:line evidence. Use when the user mentions FIX logs, FIX messages or FIX sessions, trade breaks, rejected orders, "why did order X fail / get rejected / never fill / not get acked", missing acknowledgements, execution reports, partial fills that are stuck, overfills, duplicate executions, cancel or amend (cancel/replace) rejects, sequence gaps, resend requests, heartbeat or session drops, or booking mismatches between what was ordered, executed and booked.
---

# FIX break investigator

You are investigating why an order or trade broke, using FIX logs as evidence. A deterministic
engine, `fixscan`, does the parsing, correlation and break detection and returns small JSON. You
do the reasoning: form hypotheses, test them with targeted queries, and report a root cause in
which every fact cites a log line.

## Ground rules

These are not negotiable, even if the user asks you to skip them.

1. **Go through `fixscan` for everything.** Never `cat`, `grep`, `less`, `awk`, `sed` or Read a
   log file. Logs can be gigabytes and contain client data. `head -n 20` / `tail -n 20` are
   allowed only to look at the shape of a log that fixscan cannot parse (see "When things do
   not fit"), never to read messages for the investigation.
2. **Keep masking on.** Never add `--no-mask` on your own. If the user asks for it, first ask
   them to confirm they are allowed to see that data in this conversation, and remind them that
   the output stays in the transcript. Never try to infer what a masked `***` value was.
3. **Read-only.** You diagnose and recommend. Never replay, resend, amend or cancel orders, never
   compose FIX messages for anyone to send, never suggest resetting sequence numbers or editing
   the log as a fix. Say who should act and what to ask them.
4. **No cause without evidence.** Every factual statement in the report cites `file:line` from
   fixscan output. Anything the log cannot prove is labelled as inference, with what would
   confirm it.
5. **One side's view.** A log is what one FIX engine saw. "Not in the log" means not received or
   not logged, which is not the same as never sent. Say which only when the evidence decides it.

## Running the engine

Call it as `fixscan`. The plugin puts it on the PATH. If the shell says `command not found`,
try `<this skill's directory>/../../bin/fixscan` (the skill's directory is shown when the skill
loads); if that also fails, ask the user where they installed fixscan. Do not try to build or
run the jar some other way.

- Needs Java 21+. The first run may print `building the engine (first run only)` and take a
  minute; that is normal. A `Java 21 or newer is required` message means the user must install
  a JDK; tell them and stop.
- Output is JSON on stdout. Errors are `{"error": "..."}` with exit code 2: read the message,
  fix the command (usually a path or a typo in the query) and retry once.
- **Quote every query that contains `*`, spaces or `|`**, for example
  `fixscan grep '11=ORD-0002*' day1.log`. Unquoted, the shell expands or rejects it before
  fixscan sees it (zsh fails with `no matches found`).
- All times in and out are UTC, written like `2026-01-05T09:30:00`. If the user gives a local
  time, convert it and say which conversion you made.
- Several files and `.gz` files can be passed together; pass every file that covers the
  incident (both sides, several days) in one command so orders are correlated across them.
- A 1 GB log takes seconds to tens of seconds per command. Run `stats` once and reuse what it
  told you; narrow later commands with `--session`, `--since`/`--until` and `--limit`.

| Command | Use it to |
| --- | --- |
| `fixscan stats <files...>` | What the logs contain: time range, sessions, message counts by type, parse errors |
| `fixscan scan <files...> [--since T] [--until T] [--session A-B] [--severity S] [--rule ID[,ID]] [--limit N]` | Detect breaks: counts by rule and severity, then the most severe findings with evidence |
| `fixscan order <ClOrdID or OrderID> <files...> [--limit N]` | Full timeline of one order including its amend/cancel chain, and the findings on it |
| `fixscan session <Sender-Target> <files...>` | Sequence gaps, resends, resets, logons, heartbeat silences, unanswered TestRequests |
| `fixscan grep <tag=value>[,tag=value] <files...> [--since T] [--until T] [--session A-B] [--limit N]` | Matching messages, decoded. Terms are ANDed; `*` is a wildcard; a bare tag (`103`) means "present"; tag names work too (`ClOrdID=X`) |
| `fixscan explain-tag <tag or name> [value]` | Dictionary meaning of a tag or an enum value |
| `fixscan rules` | The list of break detectors and what each one checks |

Other options: `--ack-timeout SECS` (NO_ACK threshold, default 30), `--dict FILE` (venue or firm
custom tags), `--mask-tags 50,57` (mask more tags). Keep the default JSON output; `--format text`
is for humans reading a terminal.

What each command returns, field by field, and the severity of each rule:
[references/fixscan-output.md](references/fixscan-output.md). Read it the first time you use
this skill in a conversation.

## The investigation loop

Follow these five steps in order. Do not jump to a conclusion from the first finding.

### 1. Scope

Establish what you have and what the user wants. Ask only for what is missing, in one message:
- which log files (paths, or a directory to list with `ls`);
- the subject: a ClOrdID / OrderID, a symbol, a session, a client, or a time window;
- for booking questions: what was booked (quantity, price) versus what they expected.

If the user gave enough to start, start; do not ask for confirmation.

Then run `fixscan stats` on the files, every time, before anything else. Check:
- the time range covers the incident (not the wrong day, not starting after the order);
- the expected sessions are present (and which side's log this is: who is SenderCompID on the
  orders);
- `parseErrors` and `nonFixLines`: a log with `messages: 0` is in a format fixscan does not
  recognise (see "When things do not fit").

If the logs do not cover the time, order or session asked about, stop and tell the user what the
logs do cover. Do not investigate the wrong data.

### 2. Triage

- **The user named an order:** run `fixscan order <id>` first. Its `findings` list is the
  engine's view of what went wrong with that order. Then run `fixscan scan` narrowed to that
  session and a window around the order (for example 10 minutes either side) to see whether it
  is part of something bigger.
- **Open question ("what broke today?"):** run `fixscan scan`. Read `summary.byRule`,
  `summary.bySeverity` and `summary.sessions` before the findings.

Group findings before choosing what to chase:
- by order: several findings on one `clOrdIds` chain are usually one story;
- by session and time: a cluster after a SEQ_GAP, heartbeat silence or logon usually shares
  a cause;
- by rule and reason: many identical rejects usually share one cause (static data, a limit,
  market state).

Pick the finding that answers the user's question; for an open question, the most severe group.
Tell the user in one or two lines what else you saw and are setting aside.

### 3. Hypothesize

Open [references/break-playbook.md](references/break-playbook.md) at the section for the
finding's `ruleId` (and "Combinations worth recognising" if several rules are involved). Write
down the two or three causes that fit this case, most likely first, and the query that would
confirm or rule out each. Use [references/fix-tags.md](references/fix-tags.md) or
`fixscan explain-tag` for any tag or value you are not sure of. Never guess an enum meaning.

### 4. Verify

Run the targeted queries, one hypothesis at a time:
- `fixscan order <id>`: exactly what was sent and what came back, in order, with quantities;
- `fixscan session <A-B>`: whether timing, a gap, a silence or a reconnect explains it;
- `fixscan grep`: whether it is isolated or systemic, for example `grep 35=8,103=3` (other
  orders rejected for the same reason), `grep 55=ACME,39=8`, or `grep 35=3 --session A-B`;
- compare with a healthy order of the same kind (same symbol, side, type, time): what differs
  is usually the cause.

Work the numbers yourself when the cause is quantitative (notional = OrderQty x Price against
a quoted limit; sum of LastQty against CumQty; LeavesQty against OrderQty - CumQty) and show
the arithmetic.

Mark each hypothesis **confirmed**, **ruled out** or **not decidable from these logs**, with the
line that decides it. Stop when one explanation accounts for all the evidence, or when the logs
cannot decide further. Usually 3 to 8 commands are enough; if you pass about 15, summarise what
you know and report rather than keep searching.

### 5. Report

Write the report in this structure. Keep it tight: the user should be able to forward it.

```
## Root cause
One or two sentences: what broke and why. If not proven, say "Most likely ..." and keep
confidence medium or low.

## Confidence: high | medium | low
One line on why. High: the logs state the cause directly (a reject reason that matches the
order's terms, a gap with no recovery right where the ack should be). Medium: the evidence fits
one explanation clearly better than the others. Low: the logs show the symptom but not the cause.

## Timeline (UTC)
| Time | Line | From | Message | Detail |
Only the messages that matter, oldest first.

## Evidence
Facts (from the logs):
- statement — file:line
Inference (my reading, not proven by the logs):
- statement — what would confirm it

## Ruled out
- hypothesis — why it does not hold (file:line)

## Suggested next steps
- who (venue/broker, client, trading desk, trading ops/support, middle office, static data
  team, FIX connectivity team, risk) — what to ask or check — which evidence to send them
  (OrderID, ExecIDs, MsgSeqNum ranges, times)

## Also seen
Other findings in these logs not part of this question, one line each (or "None").
```

Rules for the report:
- Cite lines exactly as fixscan printed them (`day1.log:82`). Cite the message line, not the
  finding id.
- Quote reject text exactly as fixscan printed it, masked parts included.
- Never put a masked value back, and never put a raw unmasked line in the report.
- Next steps are about people and questions, never "resend the order", "replay messages from
  N" or "reset sequence numbers".
- If there is no break (the order is fine and fully explained), say so plainly: that is a valid
  result.

## When things do not fit

- **Order not found:** check the id for typos and case; check `stats` time range; try it as the
  other id (`order` accepts both ClOrdID and OrderID); try `grep '11=<prefix>*'`, `grep 37=<id>`
  or `grep 41=<id>`. The log may be from the wrong day, session or side.
- **Several orders match (`matches` > 1):** the same id was reused on different sessions or
  days. Ask which one, or report each separately.
- **`newOrderSeen: false`:** the log starts after the order was sent. Quantity checks against
  OrderQty are limited; say so.
- **`messages: 0` or most lines are `nonFixLines`:** the log format is not recognised. Look at
  the first lines with `head -n 5 <file>`, tell the user what the format looks like and that
  fixscan expects `8=FIX...` messages with SOH, `|` or `^A` delimiters (optionally after a
  timestamp or direction prefix). Do not read further.
- **Many PARSE_ERROR findings:** the log was probably edited or exported by a tool that changed
  field contents. Findings still hold; mention it once.
- **Custom tags (usually 5000+) that matter and that `explain-tag` does not know:** ask the user
  for the counterparty's dictionary and pass it with `--dict`. Do not guess what they mean.
- **`omitted` > 0 in a scan:** narrow with `--rule`, `--session`, `--severity` or a time window
  rather than raising `--limit` far. Keep output small.
- **No findings, but the user is sure something broke:** the break may be outside FIX (booking,
  allocation, settlement) or on the other side's log. Show what the FIX log does show for that
  order (`order` timeline, CumQty, AvgPx, ExecIDs) and say where to look next.
- **Booking mismatch questions:** fixscan reads FIX only; it has no booking data. Establish from
  the FIX log what was executed (CumQty, AvgPx, and each fill's ExecID, LastQty, LastPx from
  `order`), ask the user what was booked, and compare. Look for DUPLICATE_EXEC, OVERFILL,
  QTY_MISMATCH, INVALID_STATE_TRANSITION, STUCK_PARTIAL and trade busts or corrections
  (`grep 150=H` / `grep 150=G`). See "Booking mismatches" in the playbook.

For a complete worked example of this loop, see
[references/example-investigation.md](references/example-investigation.md).
