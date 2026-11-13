package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import io.fixbreak.mask.Masker;
import io.fixbreak.rules.Finding;
import io.fixbreak.rules.Rules;
import io.fixbreak.session.DirectionState;
import io.fixbreak.session.SeqGap;
import io.fixbreak.session.SessionState;
import io.fixbreak.util.Times;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code fixscan session}: sequence number and heartbeat health of one session. */
final class SessionCommand {
    private static final int MAX_LISTED = 20;

    static Map<String, Object> run(Cli cli) throws IOException {
        if (cli.args.positional.isEmpty()) throw new UsageException("Usage: fixscan session <SenderCompID-TargetCompID> <files...>");
        String name = cli.args.positional.get(0);
        List<Path> files = cli.files(1);
        ScanConfig config = cli.config(false);
        ScanEngine engine = new ScanEngine(config, Rules.defaults());
        engine.scan(files);

        Map<String, Object> out = cli.result("session");
        SessionState s = engine.sessions.find(name);
        if (s == null) {
            out.put("query", name);
            out.put("found", false);
            List<String> names = new ArrayList<>();
            for (SessionState x : engine.sessions.sessions()) names.add(x.name);
            out.put("availableSessions", names);
            return out;
        }
        Render render = new Render(files, config);
        out.put("session", s.name);
        out.put("beginString", s.beginString);
        out.put("messages", s.messages);
        out.put("timeRange", Render.timeRange(s.firstTime, s.lastTime));
        out.put("heartBtIntSeconds", s.heartBtInt == 0 ? null : (Object) s.heartBtInt);
        out.put("logons", s.logons);
        out.put("logouts", s.logouts);
        out.put("loggedOnAtEnd", s.loggedOn);

        List<String> problems = new ArrayList<>();
        String health = "OK";
        for (DirectionState d : List.of(s.forward, s.reverse)) {
            long tooLow = d.gaps.stream().filter(g -> g.tooLow).count();
            long open = d.unrecoveredGaps() - tooLow;
            long closed = d.gaps.size() - d.unrecoveredGaps();
            if (open > 0) problems.add(open + " unrecovered sequence gap(s) on " + d.label());
            if (tooLow > 0) problems.add("MsgSeqNum went backwards " + tooLow + " time(s) on " + d.label());
            if (open > 0 || tooLow > 0) health = "BROKEN";
            if (closed > 0) problems.add(closed + " recovered sequence gap(s) on " + d.label());
            if (d.heartbeatGapCount > 0) problems.add(d.heartbeatGapCount + " heartbeat silence(s) from " + d.sender);
            if (!d.openTestRequests.isEmpty()) problems.add(d.openTestRequests.size() + " TestRequest(s) from " + d.sender + " never answered");
            if (d.hardResets > 0) problems.add(d.hardResets + " SequenceReset without GapFill from " + d.sender + " (lost messages are not resent)");
        }
        if (health.equals("OK") && !problems.isEmpty()) health = "DEGRADED";
        out.put("health", health);
        out.put("problems", problems);

        List<Object> lifecycle = new ArrayList<>();
        for (SessionState.Lifecycle l : s.lifecycle) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", l.kind());
            m.put("from", l.from());
            m.put("at", render.at(l.ref()));
            m.put("time", Times.iso(l.time()));
            if (l.text() != null) m.put("text", config.masker.text(Masker.display(l.text()), null));
            lifecycle.add(m);
        }
        out.put("lifecycle", lifecycle);
        out.put("directions", List.of(direction(s.forward, render), direction(s.reverse, render)));

        Map<String, Long> byRule = new TreeMap<>();
        for (Finding f : engine.findings.all()) {
            if (s.name.equals(f.session)) byRule.merge(f.ruleId, 1L, Long::sum);
        }
        out.put("findingsOnThisSession", byRule);
        return out;
    }

    private static Map<String, Object> direction(DirectionState d, Render render) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direction", d.label());
        m.put("messages", d.messages);
        m.put("firstSeq", d.firstSeq < 0 ? null : (Object) d.firstSeq);
        m.put("lastSeq", d.lastSeq < 0 ? null : (Object) d.lastSeq);
        m.put("heartbeats", d.heartbeats);
        m.put("testRequests", d.testRequests);
        m.put("resendRequestsSent", d.resendRequests);
        m.put("possDupResends", d.possDups);
        m.put("gapFills", d.gapFills);
        m.put("sequenceResets", d.hardResets);

        List<Object> gaps = new ArrayList<>();
        for (SeqGap g : d.gaps) {
            if (gaps.size() == MAX_LISTED) break;
            Map<String, Object> gm = new LinkedHashMap<>();
            if (g.tooLow) {
                gm.put("kind", "sequence went backwards");
                gm.put("received", g.from);
                gm.put("expected", g.to);
            } else {
                gm.put("expected", g.from);
                gm.put("received", g.to + 1);
                gm.put("missing", g.missing());
                gm.put("resendRequestAt", render.at(g.resendRequestRef));
                gm.put("recovered", g.recovered());
                if (g.recovered()) gm.put("recoveredBy", g.resentMessages + " resend(s), " + g.gapFills + " gap fill(s)");
            }
            gm.put("at", render.at(g.detectRef));
            gm.put("time", Times.iso(g.detectTime));
            gaps.add(gm);
        }
        m.put("seqGaps", gaps);
        if (d.gaps.size() > gaps.size()) m.put("seqGapsOmitted", d.gaps.size() - gaps.size());

        List<Object> silences = new ArrayList<>();
        for (DirectionState.HeartbeatGap h : d.heartbeatGaps) {
            if (silences.size() == MAX_LISTED) break;
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("silentFrom", Times.iso(h.fromTime()));
            hm.put("silentUntil", Times.iso(h.toTime()));
            hm.put("seconds", h.seconds());
            hm.put("lastMessageAt", render.at(h.afterRef()));
            hm.put("resumedAt", render.at(h.resumeRef()));
            silences.add(hm);
        }
        m.put("heartbeatSilences", silences);
        if (d.heartbeatGapCount > silences.size()) m.put("heartbeatSilencesOmitted", d.heartbeatGapCount - silences.size());

        List<Object> tests = new ArrayList<>();
        for (DirectionState.TestRequest t : d.openTestRequests) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("testReqId", t.id());
            tm.put("at", render.at(t.ref()));
            tm.put("time", Times.iso(t.time()));
            tests.add(tm);
        }
        m.put("unansweredTestRequests", tests);

        List<Object> events = new ArrayList<>();
        for (DirectionState.SeqEvent e : d.seqEvents) {
            if (events.size() == MAX_LISTED) break;
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("event", e.kind());
            if (e.kind().equals("ResendRequest")) {
                em.put("beginSeqNo", e.a());
                em.put("endSeqNo", e.b() == 0 ? "0 (to latest)" : (Object) e.b());
            } else {
                em.put("msgSeqNum", e.a());
                em.put("newSeqNo", e.b());
            }
            em.put("at", render.at(e.ref()));
            em.put("time", Times.iso(e.time()));
            events.add(em);
        }
        m.put("sequenceEvents", events);
        return m;
    }
}
