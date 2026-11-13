package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.rules.Rule;
import io.fixbreak.rules.Rules;
import io.fixbreak.util.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Entry point of the {@code fixscan} command line. Read-only: it never sends, replays or amends anything. */
public final class Main {
    public static final String VERSION = "0.1.0";

    static final String USAGE = """
            fixscan %s - find out why a trade broke by scanning FIX logs (read-only)

            Usage:
              fixscan stats <files...>                              what is in the logs: counts, sessions, time range
              fixscan scan <files...> [filters] [--limit 50]        detect breaks; summary plus the most severe findings
              fixscan order <ClOrdID|OrderID> <files...>            full timeline of one order and its amend/cancel chain
              fixscan session <Sender-Target> <files...>            sequence number and heartbeat health of one session
              fixscan grep <tag=value>[,tag=value] <files...>       matching messages, decoded (value may use *)
              fixscan explain-tag <tag> [value]                     dictionary meaning of a tag or enum value
              fixscan rules                                         list the break detectors
              fixscan generate [--out f] [--expected f] [--seed n]  write the synthetic sample log

            Filters (scan):  --since T --until T   --session A-B   --severity CRITICAL|HIGH|MEDIUM|LOW   --rule ID[,ID]
                             times are UTC, e.g. 2026-01-05T09:30:00
            Options:
              --format json|text   output format (default json)
              --limit N            cap on findings / messages / timeline entries shown
              --ack-timeout SECS   NO_ACK threshold (default 30)
              --dict FILE          extra or overriding tag definitions (JSON)
              --mask-tags 50,57    additional tags to mask
              --no-mask            show Account(1), ClientID(109), PartyID(448) and Text(58) contents unmasked

            Files may be plain text or gzip. Field delimiters SOH, '|' and '^A' are recognised.
            """.formatted(VERSION);

    public static void main(String[] argv) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int code = run(argv, out, err);
        out.flush();
        System.exit(code);
    }

    /** Runs one command. Returns the process exit code: 0 ok, 1 I/O failure, 2 usage error. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        if (argv.length == 0 || argv[0].equals("help") || argv[0].equals("--help") || argv[0].equals("-h")) {
            out.print(USAGE);
            return 0;
        }
        if (argv[0].equals("version") || argv[0].equals("--version")) {
            out.println("fixscan " + VERSION);
            return 0;
        }
        String command = argv[0];
        boolean text = false;
        try {
            Args args = new Args(argv, 1);
            if (args.flag("help")) {
                out.print(USAGE);
                return 0;
            }
            String format = args.get("format", "json");
            if (!format.equals("json") && !format.equals("text")) throw new UsageException("--format must be json or text");
            text = format.equals("text");
            Cli cli = new Cli(args, err);
            Map<String, Object> result = switch (command) {
                case "scan" -> ScanCommand.run(cli);
                case "order" -> OrderCommand.run(cli);
                case "session" -> SessionCommand.run(cli);
                case "grep" -> GrepCommand.run(cli);
                case "stats" -> StatsCommand.run(cli);
                case "explain-tag" -> ExplainTagCommand.run(cli);
                case "rules" -> rules();
                case "generate" -> GenerateCommand.run(cli);
                default -> throw new UsageException("Unknown command '" + command + "'. Run 'fixscan help'.");
            };
            out.print(text ? TextOut.render(result) : Json.write(result));
            return 0;
        } catch (UsageException e) {
            return fail(out, err, text, e.getMessage(), 2);
        } catch (NoSuchFileException e) {
            return fail(out, err, text, "File not found: " + e.getFile(), 1);
        } catch (IOException e) {
            return fail(out, err, text, "I/O error: " + e.getMessage(), 1);
        }
    }

    private static int fail(PrintStream out, PrintStream err, boolean text, String message, int code) {
        if (text) {
            err.println("fixscan: " + message);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", message);
            out.print(Json.write(m));
        }
        return code;
    }

    private static Map<String, Object> rules() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "rules");
        List<Object> list = new ArrayList<>();
        for (Rule r : Rules.defaults()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("description", r.description());
            list.add(m);
        }
        out.put("rules", list);
        return out;
    }
}
