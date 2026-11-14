package io.fixbreak.rules;

import java.util.List;

/** The registry of break detectors. Add new rules here. */
public final class Rules {
    private Rules() {}

    /** A fresh set of rule instances for one scan (some rules keep per-scan state). */
    public static List<Rule> defaults() {
        return List.of(
                new OrderRejectedRule(),
                new SessionRejectRule(),
                new BusinessRejectRule(),
                new CancelReplaceRejectedRule(),
                new NoAckRule(),
                new StuckPartialRule(),
                new OverfillRule(),
                new QtyMismatchRule(),
                new DuplicateExecRule(),
                new BrokenChainRule(),
                new SeqGapRule(),
                new InvalidStateTransitionRule(),
                new ParseErrorRule());
    }
}
