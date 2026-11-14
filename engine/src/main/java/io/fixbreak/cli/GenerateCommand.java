package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.gen.SampleGenerator;
import io.fixbreak.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code fixscan generate}: writes the synthetic sample log and the list of breaks planted in it. */
final class GenerateCommand {
    static Map<String, Object> run(Cli cli) throws IOException {
        SampleGenerator.Options opt = new SampleGenerator.Options();
        opt.seed = cli.args.getLong("seed", 42);
        opt.orders = cli.args.getInt("orders", 160);
        opt.delimiter = switch (cli.args.get("delimiter", "pipe")) {
            case "pipe", "|" -> "|";
            case "soh" -> "\u0001";
            case "caret", "^A" -> "^A";
            default -> throw new UsageException("--delimiter must be pipe, soh or caret");
        };
        Path log = Path.of(cli.args.get("out", "samples/day1.log"));
        String expectedArg = cli.args.get("expected", "samples/expected-findings.json");

        // Read-only by default: never replace an existing file (it could be a real log) unless asked.
        boolean force = cli.args.flag("force");
        List<Path> targets = new ArrayList<>(List.of(log));
        if (!expectedArg.equals("none")) targets.add(Path.of(expectedArg));
        for (Path p : targets) {
            if (Files.exists(p) && !force) {
                throw new UsageException("generate: " + p + " already exists. Pass --force to overwrite it, or choose another --out/--expected path.");
            }
        }

        if (log.getParent() != null) Files.createDirectories(log.getParent());
        SampleGenerator gen = new SampleGenerator(opt);
        List<SampleGenerator.Planted> planted;
        try (OutputStream out = Files.newOutputStream(log)) {
            planted = gen.generate(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "generate");
        result.put("out", log.toString());
        result.put("lines", gen.linesWritten());
        result.put("seed", opt.seed);
        result.put("orders", opt.orders);
        result.put("plantedBreaks", planted.size());
        if (!expectedArg.equals("none")) {
            Path expected = Path.of(expectedArg);
            if (expected.getParent() != null) Files.createDirectories(expected.getParent());
            Files.writeString(expected, Json.write(expected(planted, opt, log)), StandardCharsets.UTF_8);
            result.put("expected", expected.toString());
        }
        return result;
    }

    private static Map<String, Object> expected(List<SampleGenerator.Planted> planted, SampleGenerator.Options opt, Path log) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("description", "Breaks planted in " + log.getFileName() + " by 'fixscan generate'. "
                + "A scan of that file must report exactly these findings (ruleId + clOrdId), no more and no fewer. "
                + "clOrdId is the first ClOrdID of the order's amend/cancel chain; null for session-level findings.");
        root.put("seed", opt.seed);
        root.put("orders", opt.orders);
        List<Object> list = new ArrayList<>();
        for (SampleGenerator.Planted p : planted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", p.ruleId());
            m.put("clOrdId", p.clOrdId());
            m.put("session", p.session());
            m.put("note", p.note());
            list.add(m);
        }
        root.put("findings", list);
        return root;
    }
}
