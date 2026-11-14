package io.fixbreak.cli;

import java.util.List;
import java.util.Map;

/** Renders the same structure the JSON output uses as indented plain text for people. */
final class TextOut {
    private TextOut() {}

    static String render(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        map(sb, root, 0);
        return sb.toString();
    }

    private static void map(StringBuilder sb, Map<?, ?> m, int indent) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            entry(sb, String.valueOf(e.getKey()), e.getValue(), indent);
        }
    }

    private static void entry(StringBuilder sb, String key, Object v, int indent) {
        if (v == null) return;
        if (v instanceof Map<?, ?> m) {
            pad(sb, indent).append(key).append(":\n");
            map(sb, m, indent + 1);
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                pad(sb, indent).append(key).append(": (none)\n");
            } else if (l.get(0) instanceof Map) {
                pad(sb, indent).append(key).append(":\n");
                for (Object item : l) item(sb, key, (Map<?, ?>) item, indent + 1);
            } else if (key.equals("fields")) {
                pad(sb, indent).append(join(l, " | ")).append('\n');
            } else {
                pad(sb, indent).append(key).append(": ").append(join(l, ", ")).append('\n');
            }
        } else {
            pad(sb, indent).append(key).append(": ").append(v).append('\n');
        }
    }

    private static void item(StringBuilder sb, String listKey, Map<?, ?> m, int indent) {
        if (m.containsKey("ruleId") && m.containsKey("summary")) {
            finding(sb, m, indent);
        } else if (listKey.equals("timeline")) {
            timeline(sb, m, indent);
        } else if (listKey.equals("evidence") && m.containsKey("excerpt")) {
            pad(sb, indent).append(m.get("at")).append("  ").append(m.get("excerpt")).append('\n');
        } else {
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() == null) continue;
                int start = sb.length();
                entry(sb, String.valueOf(e.getKey()), e.getValue(), indent + 1);
                if (first && sb.length() > start) {
                    // turn the first line's indentation into a "- " bullet
                    sb.replace(start + indent * 2, start + indent * 2 + 2, "- ");
                    first = false;
                }
            }
        }
    }

    private static void finding(StringBuilder sb, Map<?, ?> f, int indent) {
        pad(sb, indent).append('[').append(f.get("severity")).append("] ").append(f.get("id"));
        if (f.get("session") != null) sb.append("  ").append(f.get("session"));
        if (f.get("clOrdIds") instanceof List<?> ids && !ids.isEmpty()) sb.append("  ").append(join(ids, " > "));
        if (f.get("time") != null) sb.append("  ").append(f.get("time"));
        sb.append('\n');
        pad(sb, indent + 1).append(f.get("summary")).append('\n');
        if (f.get("evidence") instanceof List<?> ev) {
            for (Object o : ev) {
                if (o instanceof Map<?, ?> e) pad(sb, indent + 1).append(e.get("at")).append("  ").append(e.get("excerpt")).append('\n');
                else pad(sb, indent + 1).append(o).append('\n');
            }
        }
        sb.append('\n');
    }

    private static void timeline(StringBuilder sb, Map<?, ?> t, int indent) {
        pad(sb, indent).append(t.get("time")).append("  ").append(t.get("at")).append("  ")
                .append(t.get("from")).append("  ").append(t.get("msgType"));
        for (Map.Entry<?, ?> e : t.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.equals("time") || k.equals("at") || k.equals("from") || k.equals("msgType") || e.getValue() == null) continue;
            sb.append("  ").append(k).append('=').append(e.getValue());
        }
        sb.append('\n');
    }

    private static String join(List<?> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (Object o : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(o);
        }
        return sb.toString();
    }

    private static StringBuilder pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
        return sb;
    }
}
