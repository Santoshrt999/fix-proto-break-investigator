package io.fixbreak.dict;

import io.fixbreak.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tag names and enum meanings. Starts from the built-in FIX 4.2/4.4/5.0SP2 subset and can be
 * extended or overridden with a user file of the same shape (for venue-specific custom tags).
 */
public final class FixDictionary {
    private static final String RESOURCE = "/io/fixbreak/dict/fix-dictionary.json";

    private final Map<Integer, String> names = new TreeMap<>();
    private final Map<Integer, Map<String, String>> values = new HashMap<>();
    private final Map<String, Integer> byName = new HashMap<>();

    private FixDictionary() {}

    public static FixDictionary builtin() {
        FixDictionary d = new FixDictionary();
        try (InputStream in = FixDictionary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing resource " + RESOURCE);
            d.merge(new String(in.readAllBytes(), StandardCharsets.UTF_8), RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
        return d;
    }

    /** Merges a user dictionary on top of what is already loaded. */
    public void overlay(Path file) throws IOException {
        merge(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    private void merge(String json, String source) {
        Object root;
        try {
            root = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Dictionary " + source + ": " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("tags") instanceof Map<?, ?> tags)) {
            throw new IllegalArgumentException("Dictionary " + source + ": expected {\"tags\": {\"<number>\": {\"name\": ..., \"values\": {...}}}}");
        }
        for (Map.Entry<?, ?> e : tags.entrySet()) {
            int tag;
            try {
                tag = Integer.parseInt(String.valueOf(e.getKey()).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Dictionary " + source + ": tag key '" + e.getKey() + "' is not a number");
            }
            if (!(e.getValue() instanceof Map<?, ?> def)) continue;
            if (def.get("name") instanceof String name) {
                names.put(tag, name);
                byName.put(name.toLowerCase(Locale.ROOT), tag);
            }
            if (def.get("values") instanceof Map<?, ?> vals) {
                Map<String, String> target = values.computeIfAbsent(tag, k -> new LinkedHashMap<>());
                for (Map.Entry<?, ?> v : vals.entrySet()) {
                    target.put(String.valueOf(v.getKey()), String.valueOf(v.getValue()));
                }
            }
        }
    }

    /** Tag name, or null if the tag is not in the dictionary. */
    public String tagName(int tag) {
        return names.get(tag);
    }

    /** Meaning of an enum value, or null if unknown. */
    public String valueName(int tag, String value) {
        Map<String, String> m = values.get(tag);
        return m == null || value == null ? null : m.get(value);
    }

    public Map<String, String> valuesOf(int tag) {
        Map<String, String> m = values.get(tag);
        return m == null ? Map.of() : m;
    }

    /** Resolves "35" or "MsgType" (case-insensitive) to a tag number, or null. */
    public Integer resolveTag(String numberOrName) {
        String s = numberOrName.trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return byName.get(s.toLowerCase(Locale.ROOT));
        }
    }

    public String msgTypeName(String msgType) {
        String n = valueName(35, msgType);
        return n == null ? "MsgType " + msgType : n;
    }

    /** "8 (Rejected)" when the meaning is known, otherwise just the value. Null stays null. */
    public String describe(int tag, String value) {
        if (value == null) return null;
        String n = valueName(tag, value);
        return n == null ? value : value + " (" + n + ")";
    }

    /** "OrdStatus(39)" or "Tag 9999". */
    public String label(int tag) {
        String n = names.get(tag);
        return n == null ? "Tag" + tag + "(" + tag + ")" : n + "(" + tag + ")";
    }

    public int size() {
        return names.size();
    }
}
