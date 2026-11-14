package io.fixbreak.cli;

import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import io.fixbreak.parser.FixLineParser;
import io.fixbreak.rules.Finding;
import io.fixbreak.rules.Rules;
import io.fixbreak.session.SessionState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code fixscan scan}: counts by rule plus the most severe findings, capped so the output stays small. */
final class ScanCommand {
    static Map<String, Object> run(Cli cli) throws IOException {
        List<Path> files = cli.files(0);
        ScanConfig config = cli.config(true);
        int limit = Math.max(0, cli.args.getInt("limit", 50));
        ScanEngine engine = new ScanEngine(config, Rules.defaults());
        engine.scan(files);

        Map<String, Object> out = cli.result("scan");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("messages", engine.messages);
        summary.put("orders", engine.book.orders().size());
        summary.put("timeRange", Render.timeRange(engine.firstTime, engine.lastTime));
        List<Object> sessions = new ArrayList<>();
        for (SessionState s : engine.sessions.sessions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("messages", s.messages);
            m.put("seqGaps", s.totalGaps());
            m.put("unrecoveredSeqGaps", s.unrecoveredGaps());
            m.put("heartbeatGaps", s.heartbeatGaps());
            sessions.add(m);
        }
        summary.put("sessions", sessions);
        long parseErrors = 0;
        for (FixLineParser.Issue i : FixLineParser.Issue.values()) parseErrors += engine.issueCount(i);
        summary.put("parseErrors", parseErrors);
        summary.put("findings", engine.findings.total());
        Map<String, Object> bySeverity = new LinkedHashMap<>();
        engine.findings.countBySeverity().forEach((k, v) -> bySeverity.put(k.name(), v));
        summary.put("bySeverity", bySeverity);
        summary.put("byRule", new LinkedHashMap<>(engine.findings.countByRule()));
        out.put("summary", summary);

        List<Finding> sorted = engine.findings.sorted();
        List<Finding> shown = sorted.subList(0, Math.min(limit, sorted.size()));
        Render render = new Render(files, config);
        render.loadEvidence(shown);
        List<Object> list = new ArrayList<>();
        for (Finding f : shown) list.add(render.finding(f, true));
        out.put("findings", list);
        long omitted = engine.findings.total() - shown.size();
        if (omitted > 0) {
            out.put("omitted", omitted);
            out.put("hint", "Showing the " + shown.size() + " most severe. Narrow with --rule, --session, --severity, --since/--until, or raise --limit.");
        }
        return out;
    }
}
