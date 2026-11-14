package io.fixbreak.cli;

import io.fixbreak.dict.FixDictionary;
import io.fixbreak.engine.EvidenceResolver;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.mask.Masker;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.Refs;
import io.fixbreak.rules.Finding;
import io.fixbreak.util.Times;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the output structures shared by several commands. */
final class Render {
    static final int EXCERPT_MAX = 300;

    /** Tags worth showing on an order timeline, in display order. */
    private static final int[] TIMELINE_TAGS = {
            34, 43, 11, 41, 37, 17, 150, 39, 20, 55, 54, 38, 40, 44, 59, 32, 31, 14, 151, 6,
            103, 102, 434, 45, 372, 371, 373, 379, 380, 378, 58};

    private final List<Path> files;
    private final ScanConfig config;
    final EvidenceResolver resolver;

    Render(List<Path> files, ScanConfig config) {
        this.files = files;
        this.config = config;
        this.resolver = new EvidenceResolver(files);
    }

    /** {@code day1.log:412} */
    String at(long ref) {
        return ref == 0 ? null : files.get(Refs.file(ref)).getFileName() + ":" + Refs.line(ref);
    }

    /** Loads the evidence lines of all given findings in one pass over the files. */
    void loadEvidence(List<Finding> findings) throws IOException {
        List<Long> refs = new ArrayList<>();
        for (Finding f : findings) for (long r : f.evidence) refs.add(r);
        resolver.load(refs);
    }

    /** @param excerpts include masked message text for each evidence line, not just its location */
    Map<String, Object> finding(Finding f, boolean excerpts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id);
        m.put("ruleId", f.ruleId);
        m.put("severity", f.severity.name());
        if (f.session != null) m.put("session", f.session);
        if (!f.clOrdIds.isEmpty()) m.put("clOrdIds", f.clOrdIds);
        if (f.firstTime != Times.UNKNOWN && f.firstTime != f.lastTime) m.put("firstTime", Times.iso(f.firstTime));
        if (f.lastTime != Times.UNKNOWN) m.put("time", Times.iso(f.lastTime));
        m.put("summary", f.summary);
        long[] refs = f.evidence.clone();
        Arrays.sort(refs);
        List<Object> ev = new ArrayList<>();
        for (long ref : refs) {
            if (!excerpts) {
                ev.add(at(ref));
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", at(ref));
            e.put("excerpt", resolver.excerpt(ref, config.masker, EXCERPT_MAX));
            ev.add(e);
        }
        m.put("evidence", ev);
        return m;
    }

    /** One decoded, masked message for an order timeline. */
    Map<String, Object> timelineEntry(EvidenceResolver.Line line) {
        Map<String, Object> m = new LinkedHashMap<>();
        FixMessage msg = line.msg();
        m.put("at", line.location());
        if (msg == null) {
            m.put("msgType", "<unparseable line>");
            return m;
        }
        FixDictionary dict = config.dict;
        Masker masker = config.masker;
        m.put("time", Times.iso(msg.time()));
        m.put("from", msg.get(49));
        String type = msg.msgType();
        m.put("msgType", type == null ? "?" : dict.msgTypeName(type));
        for (int tag : TIMELINE_TAGS) {
            String v = msg.get(tag);
            if (v == null) continue;
            String name = dict.tagName(tag);
            String shown = masker.value(tag, Masker.display(v), msg);
            if (masker.masksWholeTag(tag)) {
                // never decode a masked value: its meaning would reveal it
            } else if (tag == 371) {
                Integer ref = dict.resolveTag(v);
                if (ref != null && dict.tagName(ref) != null) shown = v + " (" + dict.tagName(ref) + ")";
            } else if (tag == 372) {
                shown = v + " (" + dict.msgTypeName(v) + ")";
            } else if (dict.valueName(tag, v) != null) {
                shown = dict.describe(tag, v);
            }
            m.put(name == null ? "Tag" + tag : name, shown);
        }
        return m;
    }

    /** Longest field value shown by grep; long free text is cut so output stays small. */
    static final int MAX_VALUE = 300;

    /** Every field of a message as {@code Name(tag)=value (meaning)}, masked. Framing tags 8, 9 and 10 are left out. */
    List<String> decode(FixMessage msg) {
        List<String> out = new ArrayList<>();
        FixDictionary dict = config.dict;
        for (int i = 0; i < msg.fieldCount(); i++) {
            int tag = msg.tagAt(i);
            if (tag == 8 || tag == 9 || tag == 10) continue;
            String v = msg.valueAt(i);
            String shown = config.masker.value(tag, Masker.display(v), msg);
            String meaning = config.masker.masksWholeTag(tag) ? null : dict.valueName(tag, v);
            if (shown != null && shown.length() > MAX_VALUE) shown = shown.substring(0, MAX_VALUE - 3) + "...";
            out.add(dict.label(tag) + "=" + shown + (meaning == null ? "" : " (" + meaning + ")"));
        }
        return out;
    }

    static Map<String, Object> timeRange(long first, long last) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("first", Times.iso(first));
        m.put("last", Times.iso(last));
        return m;
    }
}
