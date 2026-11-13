package io.fixbreak.cli;

import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import io.fixbreak.parser.FixLineParser.Issue;
import io.fixbreak.session.SessionState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** {@code fixscan stats}: what is in these files, before any analysis. */
final class StatsCommand {
    static Map<String, Object> run(Cli cli) throws IOException {
        List<Path> files = cli.files(0);
        ScanConfig config = cli.config(false);
        ScanEngine engine = new ScanEngine(config, List.of()); // no rules: sessions and counts only
        engine.scan(files);

        Map<String, Object> out = cli.result("stats");
        List<Object> fileList = new ArrayList<>();
        for (ScanEngine.FileStat f : engine.files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", f.name());
            m.put("bytes", f.bytes());
            m.put("lines", f.lines());
            fileList.add(m);
        }
        out.put("files", fileList);
        out.put("messages", engine.messages);
        out.put("nonFixLines", engine.nonFixLines);
        out.put("timeRange", Render.timeRange(engine.firstTime, engine.lastTime));
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("badCheckSum", engine.issueCount(Issue.BAD_CHECKSUM));
        errors.put("badBodyLength", engine.issueCount(Issue.BAD_BODY_LENGTH));
        errors.put("truncated", engine.issueCount(Issue.TRUNCATED));
        errors.put("malformed", engine.issueCount(Issue.MALFORMED));
        out.put("parseErrors", errors);

        Map<String, Object> byType = new LinkedHashMap<>();
        engine.byMsgType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> byType.put(config.dict.msgTypeName(e.getKey()) + " (" + e.getKey() + ")", e.getValue()[0]));
        out.put("byMsgType", byType);

        TreeSet<String> versions = new TreeSet<>();
        List<Object> sessions = new ArrayList<>();
        for (SessionState s : engine.sessions.sessions()) {
            if (s.beginString != null) versions.add(s.beginString);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("beginString", s.beginString);
            m.put("messages", s.messages);
            m.put("first", io.fixbreak.util.Times.iso(s.firstTime));
            m.put("last", io.fixbreak.util.Times.iso(s.lastTime));
            m.put("logons", s.logons);
            m.put("logouts", s.logouts);
            sessions.add(m);
        }
        out.put("fixVersions", new ArrayList<>(versions));
        out.put("sessions", sessions);
        return out;
    }
}
