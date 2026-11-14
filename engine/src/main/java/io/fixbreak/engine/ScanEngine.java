package io.fixbreak.engine;

import io.fixbreak.order.OrderBook;
import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.FixLineParser;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.LineReader;
import io.fixbreak.parser.ParseIssue;
import io.fixbreak.rules.FindingCollector;
import io.fixbreak.rules.MessageContext;
import io.fixbreak.rules.Rule;
import io.fixbreak.rules.ScanContext;
import io.fixbreak.session.SessionTracker;
import io.fixbreak.util.Times;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One streaming pass over the log files: parse each line, update session and order state,
 * and let every rule look at the result. Nothing but compact state is retained, so memory
 * grows with the number of orders and sessions, not with the size of the log.
 */
public final class ScanEngine {
    public record FileStat(String name, long bytes, long lines) {}

    public final ScanConfig config;
    public final SessionTracker sessions = new SessionTracker();
    public final OrderBook book = new OrderBook();
    public final FindingCollector findings;
    private final List<Rule> rules;

    public final List<FileStat> files = new ArrayList<>();
    public final Map<String, long[]> byMsgType = new TreeMap<>();
    public final Map<FixLineParser.Issue, long[]> issues = new EnumMap<>(FixLineParser.Issue.class);
    public long messages;
    public long nonFixLines;
    public long firstTime = Times.UNKNOWN;
    public long lastTime = Times.UNKNOWN;

    public ScanEngine(ScanConfig config, List<Rule> rules) {
        this.config = config;
        this.rules = rules;
        this.findings = new FindingCollector(config);
    }

    public void scan(List<Path> paths) throws IOException {
        FixLineParser parser = new FixLineParser();
        FixLineParser.Result res = new FixLineParser.Result();
        MessageContext ctx = new MessageContext(config);
        boolean trackOrders = !rules.isEmpty();
        List<String> names = new ArrayList<>();

        for (int fileIdx = 0; fileIdx < paths.size(); fileIdx++) {
            Path path = paths.get(fileIdx);
            names.add(path.getFileName().toString());
            try (LineReader reader = LineReader.open(path)) {
                String line;
                while ((line = reader.next()) != null) {
                    int from = 0;
                    for (int part = 0; from >= 0; part++) {
                    if (!parser.parse(line, from, part, fileIdx, reader.lineNo(), res)) {
                        if (part == 0 && !line.isBlank()) nonFixLines++;
                        break;
                    }
                    from = res.next;
                    FixMessage m = res.msg;
                    if (res.issue != null) {
                        issues.computeIfAbsent(res.issue, k -> new long[1])[0]++;
                        ParseIssue issue = new ParseIssue(res.issue, res.detail, fileIdx, reader.lineNo(), part,
                                m == null ? Times.UNKNOWN : m.time());
                        for (Rule r : rules) r.onParseIssue(issue, findings);
                    }
                    if (m == null) continue;
                    messages++;
                    long t = m.time();
                    if (t != Times.UNKNOWN) {
                        if (firstTime == Times.UNKNOWN || t < firstTime) firstTime = t;
                        if (lastTime == Times.UNKNOWN || t > lastTime) lastTime = t;
                    }
                    // Even a truncated line keeps its header, so sequence tracking stays accurate.
                    SessionTracker.Update su = sessions.onMessage(m);
                    String type = m.msgType();
                    byMsgType.computeIfAbsent(type == null ? "?" : type, k -> new long[1])[0]++;
                    if (!res.complete || su == null || su.duplicateResend) continue;

                    OrderUpdate ou = trackOrders ? book.onMessage(m, su) : null;
                    ctx.msg = m;
                    ctx.msgType = type;
                    ctx.direction = su.direction;
                    ctx.session = su.direction.session;
                    ctx.newGap = su.newGap;
                    ctx.update = ou;
                    for (Rule r : rules) r.onMessage(ctx, findings);
                    }
                }
                if (reader.damaged() != null) {
                    issues.computeIfAbsent(FixLineParser.Issue.TRUNCATED, k -> new long[1])[0]++;
                    ParseIssue issue = new ParseIssue(FixLineParser.Issue.TRUNCATED, reader.damaged(), fileIdx,
                            reader.lineNo() + 1, lastTime);
                    for (Rule r : rules) r.onParseIssue(issue, findings);
                }
                files.add(new FileStat(path.toString(), Files.size(path), reader.lineNo()));
            }
        }
        ScanContext end = new ScanContext(config, names, sessions.sessions(), book.orders(), lastTime);
        for (Rule r : rules) r.onEnd(end, findings);
    }

    public long issueCount(FixLineParser.Issue kind) {
        long[] c = issues.get(kind);
        return c == null ? 0 : c[0];
    }
}
