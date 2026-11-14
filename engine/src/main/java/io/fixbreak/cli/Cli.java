package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.dict.FixDictionary;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.mask.Masker;
import io.fixbreak.rules.Severity;
import io.fixbreak.util.Times;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What every command needs: parsed arguments, the configured dictionary and masker, and the input files. */
final class Cli {
    static final String NO_MASK_WARNING =
            "WARNING: masking is OFF (--no-mask). Output may contain account numbers, client identifiers and names. "
                    + "Do not paste it into tickets, chats or AI tools unless you are allowed to share that data.";

    final Args args;
    final PrintStream err;
    private ScanConfig config;

    Cli(Args args, PrintStream err) {
        this.args = args;
        this.err = err;
    }

    /** Dictionary, masking and scan settings from the command line. Finding filters are only applied when asked. */
    ScanConfig config(boolean withFilters) throws IOException {
        if (config == null) {
            ScanConfig c = new ScanConfig();
            String dict = args.get("dict");
            if (dict != null) {
                Path p = Path.of(dict);
                if (!Files.isRegularFile(p)) throw new UsageException("Dictionary file not found: " + dict);
                try {
                    c.dict = FixDictionary.builtin();
                    c.dict.overlay(p);
                } catch (IllegalArgumentException e) {
                    throw new UsageException(e.getMessage());
                }
            }
            Set<Integer> extra = new HashSet<>();
            String maskTags = args.get("mask-tags");
            if (maskTags != null) {
                for (String t : maskTags.split(",")) {
                    Integer tag = c.dict.resolveTag(t);
                    if (tag == null) throw new UsageException("--mask-tags: unknown tag '" + t.trim() + "'");
                    extra.add(tag);
                }
            }
            boolean mask = !args.flag("no-mask");
            if (!mask) err.println(NO_MASK_WARNING);
            c.masker = new Masker(mask, extra);
            c.ackTimeoutMillis = args.getLong("ack-timeout", 30) * 1000L;
            if (c.ackTimeoutMillis <= 0) throw new UsageException("--ack-timeout must be a positive number of seconds");
            config = c;
        }
        if (withFilters) {
            config.sessionFilter = args.get("session");
            String sev = args.get("severity");
            if (sev != null) {
                try {
                    config.minSeverity = Severity.parse(sev);
                } catch (IllegalArgumentException e) {
                    throw new UsageException("--severity must be one of CRITICAL, HIGH, MEDIUM, LOW, INFO");
                }
            }
            String rule = args.get("rule");
            if (rule != null) {
                Set<String> known = new java.util.TreeSet<>();
                for (io.fixbreak.rules.Rule r : io.fixbreak.rules.Rules.defaults()) known.add(r.id());
                Set<String> ids = new HashSet<>();
                for (String r : rule.split(",")) {
                    String id = r.trim().toUpperCase(java.util.Locale.ROOT);
                    if (!known.contains(id)) throw new UsageException("--rule: unknown rule '" + r.trim() + "'. Known rules: " + String.join(", ", known));
                    ids.add(id);
                }
                config.ruleFilter = ids;
            }
            config.since = time("since");
            config.until = time("until");
        }
        return config;
    }

    long time(String option) {
        String v = args.get(option);
        if (v == null) return Times.UNKNOWN;
        long t = parseCliTime(v.trim());
        if (t == Times.UNKNOWN) {
            throw new UsageException("--" + option + " must be a valid time like 2026-01-05T09:30:00 (UTC), 2026-01-05T11:30:00+02:00"
                    + " or 20260105-09:30:00, got '" + v + "'");
        }
        return t;
    }

    /** Strict CLI time parsing: ISO with or without offset (no offset = UTC), or FIX UTCTimestamp. Out-of-range fields are rejected. */
    static long parseCliTime(String v) {
        try {
            return java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // try the next form
        }
        try {
            return java.time.LocalDateTime.parse(v.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // try the next form
        }
        try {
            java.time.format.DateTimeFormatter fix = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HH:mm:ss[.SSS]")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT);
            return java.time.LocalDateTime.parse(v, fix).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            return Times.UNKNOWN;
        }
    }

    /** Input files from the positional arguments starting at {@code from}. */
    List<Path> files(int from) {
        List<Path> out = new ArrayList<>();
        for (int i = from; i < args.positional.size(); i++) {
            Path p = Path.of(args.positional.get(i));
            if (!Files.isRegularFile(p)) throw new UsageException("Log file not found: " + p);
            out.add(p);
        }
        if (out.isEmpty()) throw new UsageException("No log files given");
        return out;
    }

    Map<String, Object> result(String command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        m.put("masking", args.flag("no-mask") ? "OFF" : "on");
        return m;
    }
}
