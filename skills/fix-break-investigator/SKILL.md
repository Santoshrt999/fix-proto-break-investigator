---
name: fix-break-investigator
description: Investigates why a trade or order broke by analysing FIX protocol logs with the read-only fixscan engine. Use when the user mentions FIX logs, FIX messages or FIX sessions, trade breaks, rejected orders, "why did order X fail / get rejected / never fill", missing acknowledgements, execution reports, partial fills that are stuck, overfills, duplicate executions, cancel or amend rejects, sequence gaps, resend requests, heartbeat or session drops, or booking mismatches between what was ordered and what was executed.
---

# FIX break investigator

You are investigating why an order or trade broke, using FIX logs as evidence. A deterministic
engine, `fixscan`, does the parsing, correlation and break detection. You do the reasoning:
form a hypothesis, test it with targeted queries, and report a root cause backed by log lines.

## Ground rules

- **Go through `fixscan` for everything.** Never `cat`, `grep`, `less` or Read a whole log file.
  Logs can be gigabytes and contain client data. `head`/`tail` are allowed only for a small
  excerpt (20 lines or fewer), for example to see what an unrecognised log format looks like.
- **Keep masking on.** Never pass `--no-mask` unless the user explicitly asks for it and confirms
  they are allowed to see the data. Never try to work out what a masked value (`***`) was.
- **Read-only.** You diagnose and recommend. Never suggest replaying, resending, amending or
  cancelling orders yourself, never write FIX messages for the user to inject, and never propose
  resetting sequence numbers as a quick fix. Recommend who should act instead.
- **No cause without evidence.** Every claim in the report cites `file:line` from fixscan output.
  If the logs cannot prove something, say so and label it as inference.
- **The log is one side's view.** A message that is "missing" may have been sent and lost, or never
  sent. Say which it is only if the evidence distinguishes them.

## Running the engine

Run `fixscan <command>`. If the shell reports it is not found, use
`"${CLAUDE_PLUGIN_ROOT}/bin/fixscan"` instead. The first run builds the engine (needs Java 21+)
and may take a minute. Output is JSON; all times are UTC.

| Command | Use it to |
| --- | --- |
| `fixscan stats <files...>` | See what is in the logs: time range, sessions, message counts, parse errors |
| `fixscan scan <files...> [--since T --until T --session A-B --severity S --rule ID --limit N]` | Detect breaks: counts by rule, then the most severe findings with evidence |
| `fixscan order <ClOrdID or OrderID> <files...>` | Full timeline of one order including its amend/cancel chain |
| `fixscan session <Sender-Target> <files...>` | Sequence gaps, resends, resets, logons, heartbeat silences for one session |
| `fixscan grep <tag=value>[,tag=value] <files...> [--limit N]` | Find matching messages, decoded. `*` is a wildcard; a tag alone means "present" |
| `fixscan explain-tag <tag> [value]` | Dictionary meaning of a tag or enum value |

Useful options: `--ack-timeout SECS` (NO_ACK threshold, default 30), `--dict FILE` (custom tags),
`--mask-tags 50,57` (mask more tags). Files can be gzip. Times look like `2026-01-05T09:30:00`.

## The investigation loop

Follow these steps in order. Do not skip ahead to a conclusion.

### 1. Scope

Establish, asking the user only for what they have not already given:
- which log files (paths);
- what they are worried about: a specific ClOrdID / OrderID, a symbol, a session, or a time window.

Then run `fixscan stats` on the files first, every time. Check that the time range covers the
incident, that the expected sessions are present, and whether there are parse errors. If the log
does not cover the time or session in question, stop and tell the user; do not investigate the
wrong data.

### 2. Triage

- If the user named an order, run `fixscan order <id>` straight away, then a `scan` narrowed to
  that session or time window for context.
- Otherwise run `fixscan scan`. Read `summary.byRule` first, then the findings.

Group findings before choosing what to chase:
- by order (several findings on one ClOrdID chain are usually one story);
- by session and time (a burst of problems after a SEQ_GAP or heartbeat silence usually shares a cause);
- by rule (many identical rejects usually share one cause, such as static data or a limit).

Pick the finding most relevant to the user's question; if they asked an open question, the most
severe. Tell the user what else you saw and are setting aside.

### 3. Hypothesize

Open [references/break-playbook.md](references/break-playbook.md), find the section for the rule
id, and list the plausible causes for this case. Usually two or three. State them before testing
them. Use [references/fix-tags.md](references/fix-tags.md) or `fixscan explain-tag` for any tag
or value you are not certain of. Do not guess enum meanings.

### 4. Verify

For each hypothesis, run the targeted query that would confirm or rule it out:
- `fixscan order <id>` to see exactly what was sent and what came back, in order;
- `fixscan session <A-B>` when timing, gaps or silence might explain it;
- `fixscan grep` to test whether it is isolated or systemic, for example
  `grep 35=8,103=3` (other orders rejected for the same reason) or `grep 55=ACME,39=8`.

Mark each hypothesis confirmed, ruled out, or not decidable from these logs. Compare the broken
order with a healthy one of the same kind when you can: what differs is usually the cause.
Stop when one explanation accounts for all the evidence, or when the logs cannot decide further.

### 5. Report

Use this structure:

```
## Root cause
One or two sentences. What broke and why.

## Confidence: high | medium | low
Why this level. High means the logs state the cause directly (e.g. a reject reason and matching
order terms). Medium means the evidence fits one explanation best. Low means the logs show the
symptom but not the cause.

## Timeline
UTC time, file:line, who sent what. Only the messages that matter.

## Evidence
- Facts (from the logs): each with file:line.
- Inference (my reading): clearly separated, with what would confirm it.

## Ruled out
Hypotheses you tested and why they do not hold.

## Suggested next steps
Who should do what: the venue or broker, the client, trading ops / support, the static data
team, the FIX connectivity team, risk. Include what to ask them and which evidence to send.
Never an instruction to replay or amend the trade directly.

## Also seen
Other findings in the same logs that were not part of this question, one line each.
```

Keep masked values masked in the report. Quote reject text exactly as fixscan printed it.

## Reading findings

Each finding has `id`, `ruleId`, `severity`, `session`, `clOrdIds` (the amend/cancel chain,
oldest first), `time`, a one-line `summary`, and up to five `evidence` lines (`at` = file:line,
`excerpt` = the masked message as `tag=value|...`).

Severity is about how dangerous the break is, not how relevant it is to the user's question.
A MEDIUM `ORDER_REJECTED` on the order they asked about matters more than an unrelated CRITICAL.

If `scan` reports `omitted` findings, narrow with `--rule`, `--session`, `--severity` or a time
window rather than raising `--limit` far: keep output small.

## When things do not fit

- **Order not found:** check the id for typos, check `stats` time range, try `grep 37=<id>` or
  `grep 11=<prefix>*`. The log may be from the wrong day, session or side.
- **`newOrderSeen: false`:** the log starts after the order was sent. Quantity checks are limited;
  say so.
- **Many PARSE_ERROR findings:** the log may have been edited or exported through a tool that
  altered it. Findings still hold, but mention it.
- **Custom tags (5000+) you cannot explain:** ask the user for the venue's dictionary and pass it
  with `--dict`. Do not guess.
- **No findings but the user is sure something broke:** the break may be outside FIX (booking,
  allocation, settlement) or on the other side's log. Say what the FIX log does show for that
  order and what to look at next.
