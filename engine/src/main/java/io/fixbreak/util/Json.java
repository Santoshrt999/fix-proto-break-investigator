package io.fixbreak.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer so the engine needs no runtime dependencies.
 * Objects are {@link LinkedHashMap}, arrays are {@link ArrayList}, numbers are
 * {@link Long} or {@link Double}.
 */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(4096);
        write(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Double d) {
            sb.append(number(d));
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            List<Object> items = new ArrayList<>();
            boolean scalars = true;
            for (Object o : it) {
                items.add(o);
                if (o instanceof Map || o instanceof Iterable) scalars = false;
            }
            if (items.isEmpty()) {
                sb.append("[]");
            } else if (scalars) {
                sb.append('[');
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) sb.append(", ");
                    write(sb, items.get(i), indent);
                }
                sb.append(']');
            } else {
                sb.append("[\n");
                for (int i = 0; i < items.size(); i++) {
                    pad(sb, indent + 1);
                    write(sb, items.get(i), indent + 1);
                    if (i + 1 < items.size()) sb.append(',');
                    sb.append('\n');
                }
                pad(sb, indent);
                sb.append(']');
            }
        } else {
            quote(sb, v.toString());
        }
    }

    /** Formats a double without a trailing ".0" for whole numbers. */
    public static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- reading

    public static Object parse(String text) {
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value();
        r.ws();
        if (r.pos != text.length()) throw r.error("unexpected trailing content");
        return v;
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + msg);
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            if (pos >= s.length()) throw error("unexpected end");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return num();
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) throw error("expected " + word);
            pos += word.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') throw error("expected string key");
                String k = string();
                ws();
                if (peek() != ':') throw error("expected ':'");
                pos++;
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                pos++;
                if (c == '}') return m;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            ws();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                pos++;
                if (c == ']') return l;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        char peek() {
            if (pos >= s.length()) throw error("unexpected end");
            return s.charAt(pos);
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw error("unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        Object num() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw error("unexpected character '" + s.charAt(pos) + "'");
            String t = s.substring(start, pos);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
                return Double.parseDouble(t);
            } catch (NumberFormatException ex) {
                throw error("bad number " + t);
            }
        }
    }
}
