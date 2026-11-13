package io.fixbreak.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal command line parser: positionals plus {@code --name value} options and {@code --flag} switches. */
final class Args {
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static final Set<String> VALUE_OPTIONS = Set.of(
            "format", "since", "until", "session", "severity", "limit", "dict", "mask-tags", "ack-timeout",
            "rule", "out", "expected", "seed", "orders", "delimiter");
    private static final Set<String> FLAGS = Set.of("no-mask", "help");

    final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new HashMap<>();

    Args(String[] argv, int from) {
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--") || a.length() == 2) {
                positional.add(a);
                continue;
            }
            String name = a.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq > 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (FLAGS.contains(name)) {
                options.put(name, "true");
            } else if (VALUE_OPTIONS.contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.length) throw new UsageException("Option --" + name + " needs a value");
                    value = argv[++i];
                }
                options.put(name, value);
            } else {
                throw new UsageException("Unknown option --" + name);
            }
        }
    }

    boolean flag(String name) {
        return options.containsKey(name);
    }

    String get(String name) {
        return options.get(name);
    }

    String get(String name, String dflt) {
        return options.getOrDefault(name, dflt);
    }

    int getInt(String name, int dflt) {
        String v = options.get(name);
        if (v == null) return dflt;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new UsageException("Option --" + name + " must be a whole number, got '" + v + "'");
        }
    }

    long getLong(String name, long dflt) {
        String v = options.get(name);
        if (v == null) return dflt;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new UsageException("Option --" + name + " must be a whole number, got '" + v + "'");
        }
    }
}
