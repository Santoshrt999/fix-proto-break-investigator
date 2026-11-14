package io.fixbreak.rules;

import io.fixbreak.parser.FixLineParser.Issue;
import io.fixbreak.parser.ParseIssue;
import io.fixbreak.util.Times;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lines that look like FIX but fail validation. Reported as one finding per problem kind
 * and file, so a log with thousands of bad checksums does not drown everything else.
 */
public final class ParseErrorRule implements Rule {
    private static final class Group {
        long count;
        long firstTime = Times.UNKNOWN;
        long lastTime = Times.UNKNOWN;
        String firstDetail;
        final long[] refs = new long[Finding.MAX_EVIDENCE];
        int n;
    }

    private final Map<String, Group> groups = new LinkedHashMap<>();

    @Override
    public String id() {
        return "PARSE_ERROR";
    }

    @Override
    public String description() {
        return "Lines that look like FIX but have a wrong BodyLength(9) or CheckSum(10), or are truncated or malformed.";
    }

    @Override
    public void onParseIssue(ParseIssue issue, FindingSink out) {
        Group g = groups.computeIfAbsent(issue.kind() + "#" + issue.fileIdx(), k -> new Group());
        g.count++;
        if (g.firstDetail == null) g.firstDetail = issue.detail();
        if (g.n < g.refs.length) g.refs[g.n++] = issue.ref();
        if (issue.time() != Times.UNKNOWN) {
            if (g.firstTime == Times.UNKNOWN) g.firstTime = issue.time();
            g.lastTime = issue.time();
        }
    }

    @Override
    public void onEnd(ScanContext ctx, FindingSink out) {
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            String[] key = e.getKey().split("#");
            Issue kind = Issue.valueOf(key[0]);
            String file = ctx.fileNames().get(Integer.parseInt(key[1]));
            Group g = e.getValue();
            String what = switch (kind) {
                case BAD_CHECKSUM -> "with a wrong CheckSum(10)";
                case BAD_BODY_LENGTH -> "with a wrong BodyLength(9)";
                case TRUNCATED -> "cut short before CheckSum(10)";
                case MALFORMED -> "that are malformed FIX";
            };
            String note = switch (kind) {
                case BAD_CHECKSUM, BAD_BODY_LENGTH -> "; still analysed, but the line may have been edited or corrupted";
                case TRUNCATED, MALFORMED -> "; not applied to order state";
            };
            out.report(this, Severity.LOW, null, null, g.firstTime, g.lastTime,
                    g.count + (g.count == 1 ? " line " : " lines ") + what + " in " + file + note
                            + " (first: " + g.firstDetail + ")",
                    java.util.Arrays.copyOf(g.refs, g.n));
        }
        groups.clear();
    }
}
