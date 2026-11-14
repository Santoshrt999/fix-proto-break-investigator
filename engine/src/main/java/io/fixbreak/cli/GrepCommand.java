package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.parser.FixLineParser;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.LineReader;
import io.fixbreak.util.Times;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** {@code fixscan grep}: messages whose fields match every given {@code tag=value}, decoded and masked. */
final class GrepCommand {
    private record Term(int tag, String exact, Pattern glob) {
        boolean matches(FixMessage m) {
            // a tag can repeat (repeating groups), so look at every occurrence
            for (int i = 0; i < m.fieldCount(); i++) {
                if (m.tagAt(i) != tag) continue;
                if (exact == null && glob == null) return true;
                String v = m.valueAt(i);
                if (exact != null ? exact.equals(v) : glob.matcher(v).matches()) return true;
            }
            return false;
        }
    }

    static Map<String, Object> run(Cli cli) throws IOException {
        if (cli.args.positional.isEmpty()) {
            throw new UsageException("Usage: fixscan grep <tag=value>[,tag=value] <files...>  (value may use * as a wildcard; tag alone means 'present')");
        }
        ScanConfig config = cli.config(false);
        String spec = cli.args.positional.get(0);
        List<Term> terms = new ArrayList<>();
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            String tagText = eq < 0 ? part : part.substring(0, eq);
            Integer tag = config.dict.resolveTag(tagText);
            if (tag == null) throw new UsageException("grep: unknown tag '" + tagText.trim() + "' (use a tag number, or a name from the dictionary)");
            if (eq < 0) {
                terms.add(new Term(tag, null, null));
            } else {
                String value = part.substring(eq + 1);
                if (value.indexOf('*') < 0) {
                    terms.add(new Term(tag, value, null));
                } else {
                    StringBuilder re = new StringBuilder();
                    for (String piece : value.split("\\*", -1)) {
                        if (re.length() > 0) re.append(".*");
                        re.append(Pattern.quote(piece));
                    }
                    terms.add(new Term(tag, null, Pattern.compile(re.toString())));
                }
            }
        }
        List<Path> files = cli.files(1);
        int limit = Math.max(0, cli.args.getInt("limit", 20));
        long since = cli.time("since");
        long until = cli.time("until");
        String session = cli.args.get("session");

        // Cheap pre-filter: a line cannot match unless it contains every literal value somewhere.
        List<String> literals = new ArrayList<>();
        for (Term t : terms) if (t.exact != null && !t.exact.isEmpty()) literals.add("=" + t.exact);

        Render render = new Render(files, config);
        FixLineParser parser = new FixLineParser();
        FixLineParser.Result res = new FixLineParser.Result();
        List<Object> shown = new ArrayList<>();
        long total = 0;
        for (int fileIdx = 0; fileIdx < files.size(); fileIdx++) {
            try (LineReader reader = LineReader.open(files.get(fileIdx))) {
                String line;
                next:
                while ((line = reader.next()) != null) {
                    for (String lit : literals) if (!line.contains(lit)) continue next;
                    if (!parser.parse(line, fileIdx, reader.lineNo(), res) || res.msg == null) continue;
                    FixMessage m = res.msg;
                    for (Term t : terms) if (!t.matches(m)) continue next;
                    long time = m.time();
                    if (since != Times.UNKNOWN && time != Times.UNKNOWN && time < since) continue;
                    if (until != Times.UNKNOWN && time != Times.UNKNOWN && time > until) continue;
                    String sender = m.get(49), target = m.get(56);
                    if (session != null && !(session.equalsIgnoreCase(sender + "-" + target) || session.equalsIgnoreCase(target + "-" + sender))) continue;
                    total++;
                    if (shown.size() >= limit) continue;
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("at", render.at(m.ref()));
                    e.put("time", Times.iso(time));
                    String type = m.msgType();
                    e.put("msgType", type == null ? "?" : config.dict.msgTypeName(type));
                    if (res.issue != null) e.put("parseIssue", res.issue + ": " + res.detail);
                    e.put("fields", render.decode(m));
                    shown.add(e);
                }
            }
        }
        Map<String, Object> out = cli.result("grep");
        out.put("query", spec);
        out.put("matches", total);
        out.put("shown", shown.size());
        if (total > shown.size()) out.put("hint", "Showing the first " + shown.size() + ". Add more tag=value terms, --since/--until, --session, or raise --limit.");
        out.put("messages", shown);
        return out;
    }
}
