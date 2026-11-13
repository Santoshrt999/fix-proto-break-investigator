package io.fixbreak.rules;

import io.fixbreak.engine.ScanConfig;
import io.fixbreak.order.OrderState;
import io.fixbreak.session.SessionState;
import io.fixbreak.util.Times;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects findings, applies the scan's filters, and keeps exact counts even when the
 * number of findings is too large to store.
 */
public final class FindingCollector implements FindingSink {
    private final ScanConfig config;
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, long[]> countByRule = new TreeMap<>();
    private final Map<String, long[]> seqByRule = new TreeMap<>();
    private final Map<Severity, long[]> countBySeverity = new EnumMap<>(Severity.class);
    private long total;

    public FindingCollector(ScanConfig config) {
        this.config = config;
    }

    @Override
    public void report(Rule rule, Severity severity, SessionState session, OrderState order,
                       long firstTime, long lastTime, String summary, long... evidence) {
        // Ids number every finding of a rule in log order, before filtering, so the same
        // break keeps the same id whichever filters a later query uses.
        long seq = ++seqByRule.computeIfAbsent(rule.id(), k -> new long[1])[0];

        if (!severity.atLeast(config.minSeverity)) return;
        if (config.ruleFilter != null && !config.ruleFilter.contains(rule.id())) return;
        if (config.sessionFilter != null && (session == null || !session.matches(config.sessionFilter))) return;
        if (firstTime == Times.UNKNOWN) firstTime = lastTime;
        if (lastTime == Times.UNKNOWN) lastTime = firstTime;
        if (config.since != Times.UNKNOWN && lastTime != Times.UNKNOWN && lastTime < config.since) return;
        if (config.until != Times.UNKNOWN && firstTime != Times.UNKNOWN && firstTime > config.until) return;

        total++;
        long stored = countByRule.computeIfAbsent(rule.id(), k -> new long[1])[0]++;
        countBySeverity.computeIfAbsent(severity, k -> new long[1])[0]++;
        if (stored >= config.maxStoredPerRule) return;

        long[] refs = new long[Math.min(evidence.length, Finding.MAX_EVIDENCE)];
        int n = 0;
        outer:
        for (long ref : evidence) {
            if (ref == 0 || n == refs.length) continue;
            for (int i = 0; i < n; i++) if (refs[i] == ref) continue outer;
            refs[n++] = ref;
        }
        findings.add(new Finding(rule.id() + "-" + seq, rule.id(), severity,
                session == null ? null : session.name,
                order == null ? List.of() : order.chain(),
                firstTime, lastTime, summary, java.util.Arrays.copyOf(refs, n)));
    }

    public long total() {
        return total;
    }

    public Map<String, Long> countByRule() {
        Map<String, Long> out = new TreeMap<>();
        countByRule.forEach((k, v) -> out.put(k, v[0]));
        return out;
    }

    public Map<Severity, Long> countBySeverity() {
        Map<Severity, Long> out = new EnumMap<>(Severity.class);
        countBySeverity.forEach((k, v) -> out.put(k, v[0]));
        return out;
    }

    /** Stored findings, most severe first, then in time order. */
    public List<Finding> sorted() {
        List<Finding> out = new ArrayList<>(findings);
        out.sort(Comparator.<Finding, Severity>comparing(f -> f.severity)
                .thenComparingLong(f -> f.lastTime == Times.UNKNOWN ? Long.MAX_VALUE : f.lastTime)
                .thenComparing(f -> f.id));
        return out;
    }

    public List<Finding> all() {
        return findings;
    }
}
