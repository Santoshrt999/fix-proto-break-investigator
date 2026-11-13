package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.dict.FixDictionary;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code fixscan explain-tag}: what a tag, or one of its values, means. */
final class ExplainTagCommand {
    static Map<String, Object> run(Cli cli) throws IOException {
        List<String> p = cli.args.positional;
        if (p.isEmpty()) throw new UsageException("Usage: fixscan explain-tag <tag number or name> [value]");
        FixDictionary dict = cli.config(false).dict;
        String query = p.get(0);
        String value = p.size() > 1 ? p.get(1) : null;
        // also accept "39=8"
        int eq = query.indexOf('=');
        if (eq > 0 && value == null) {
            value = query.substring(eq + 1);
            query = query.substring(0, eq);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "explain-tag");
        Integer tag = dict.resolveTag(query);
        if (tag == null || dict.tagName(tag) == null) {
            out.put("query", query);
            out.put("known", false);
            out.put("hint", "Not in the built-in FIX 4.2/4.4/5.0SP2 subset. If it is a venue or firm custom tag, describe it in a JSON file and pass --dict <file>.");
            return out;
        }
        out.put("tag", tag);
        out.put("name", dict.tagName(tag));
        Map<String, String> values = dict.valuesOf(tag);
        if (value != null) {
            out.put("value", value);
            String meaning = values.get(value);
            out.put("meaning", meaning != null ? meaning
                    : values.isEmpty() ? "free-form value (this tag has no enumerated values in the dictionary)"
                    : "not a known value for this tag in the dictionary");
            if (meaning == null && !values.isEmpty()) out.put("knownValues", new LinkedHashMap<>(values));
        } else if (!values.isEmpty()) {
            out.put("values", new LinkedHashMap<>(values));
        }
        return out;
    }
}
