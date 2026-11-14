package io.fixbreak.rules;

import io.fixbreak.session.DirectionState;
import io.fixbreak.session.SeqGap;
import io.fixbreak.session.SessionState;

/** MsgSeqNum gaps, reported with whether they were recovered. */
public final class SeqGapRule implements Rule {
    @Override
    public String id() {
        return "SEQ_GAP";
    }

    @Override
    public String description() {
        return "MsgSeqNum(34) skipped or went backwards. Reports whether a ResendRequest was sent and whether resends or gap fills recovered it.";
    }

    @Override
    public void onEnd(ScanContext ctx, FindingSink out) {
        for (SessionState s : ctx.sessions()) {
            report(s.forward, out);
            report(s.reverse, out);
        }
    }

    private void report(DirectionState d, FindingSink out) {
        for (SeqGap g : d.gaps) {
            if (g.tooLow && g.hardReset) {
                out.report(this, Severity.HIGH, d.session, null, g.detectTime, g.detectTime,
                        "SequenceReset without GapFill on " + d.label() + " set NewSeqNo(36)=" + g.from + ", below the expected "
                                + g.to + " (the FIX spec does not allow lowering the sequence number this way)",
                        g.detectRef, g.beforeRef);
                continue;
            }
            if (g.tooLow) {
                out.report(this, Severity.HIGH, d.session, null, g.detectTime, g.detectTime,
                        "MsgSeqNum went backwards on " + d.label() + ": received " + g.from + " when " + g.to
                                + " was expected, without PossDupFlag(43) or a sequence reset",
                        g.detectRef, g.beforeRef);
                continue;
            }
            StringBuilder sb = new StringBuilder("Sequence gap on ").append(d.label()).append(": expected ")
                    .append(g.from).append(", received ").append(g.to + 1).append(" (").append(g.missing())
                    .append(g.missing() == 1 ? " message missing" : " messages missing").append(')');
            if (g.hardReset) {
                sb.append("; skipped by a SequenceReset without GapFill, so the sender declared those messages lost");
            } else {
                sb.append(g.resendRequestRef != 0 ? "; ResendRequest sent" : "; no ResendRequest seen");
            }
            if (g.hardReset) {
                // nothing to add: a hard reset can never be recovered
            } else if (g.recovered()) {
                sb.append("; recovered by ");
                if (g.resentMessages > 0) sb.append(g.resentMessages).append(" PossDup resend(s)");
                if (g.resentMessages > 0 && g.gapFills > 0) sb.append(" and ");
                if (g.gapFills > 0) sb.append(g.gapFills).append(" SequenceReset-GapFill");
            } else if (g.coveredTo >= g.from) {
                sb.append("; only partly recovered (up to ").append(g.coveredTo).append(')');
            } else {
                sb.append("; NOT recovered, those messages never appear in the log");
            }
            out.report(this, g.recovered() ? Severity.LOW : Severity.HIGH, d.session, null, g.detectTime, g.detectTime,
                    sb.toString(), g.beforeRef, g.detectRef, g.resendRequestRef, g.firstRecoveryRef, g.lastRecoveryRef);
        }
    }
}
