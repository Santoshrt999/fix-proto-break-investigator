package io.fixbreak.rules;

import java.util.List;

/**
 * One detected break. Evidence is held as line references and only turned into masked
 * excerpts for the findings that are actually printed.
 */
public final class Finding {
    public static final int MAX_EVIDENCE = 5;

    public final String id;
    public final String ruleId;
    public final Severity severity;
    /** Session name, e.g. BUYSIDE1-BROKERX. Null for findings not tied to a session. */
    public final String session;
    /** The order's ClOrdID chain, oldest first. Empty for session-level findings. */
    public final List<String> clOrdIds;
    public final long firstTime;
    public final long lastTime;
    public final String summary;
    /** Up to {@link #MAX_EVIDENCE} line references (see {@link io.fixbreak.parser.Refs}). */
    public final long[] evidence;

    Finding(String id, String ruleId, Severity severity, String session, List<String> clOrdIds,
            long firstTime, long lastTime, String summary, long[] evidence) {
        this.id = id;
        this.ruleId = ruleId;
        this.severity = severity;
        this.session = session;
        this.clOrdIds = clOrdIds;
        this.firstTime = firstTime;
        this.lastTime = lastTime;
        this.summary = summary;
        this.evidence = evidence;
    }

    /** First ClOrdID of the chain, or null for session-level findings. */
    public String rootClOrdId() {
        return clOrdIds.isEmpty() ? null : clOrdIds.get(0);
    }
}
