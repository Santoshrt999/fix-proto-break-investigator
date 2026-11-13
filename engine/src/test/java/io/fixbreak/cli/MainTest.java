package io.fixbreak.cli;

import io.fixbreak.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests of the command line against the committed sample log. */
class MainTest {
    private static final String LOG = Path.of("..", "samples", "day1.log").toString();

    private String stdout;
    private String stderr;

    private int run(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(argv, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        stdout = out.toString(StandardCharsets.UTF_8);
        stderr = err.toString(StandardCharsets.UTF_8);
        return code;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String... argv) {
        assertEquals(0, run(argv), () -> stdout + stderr);
        return (Map<String, Object>) Json.parse(stdout);
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object root, Object... path) {
        Object o = root;
        for (Object p : path) o = p instanceof Integer i ? ((List<Object>) o).get(i) : ((Map<String, Object>) o).get(p);
        return (T) o;
    }

    @Test
    void scanGivesSummaryAndFindings() {
        Map<String, Object> r = json("scan", LOG);
        assertEquals("on", r.get("masking"));
        assertEquals(18L, (Long) at(r, "summary", "findings"));
        assertEquals(3L, (Long) at(r, "summary", "byRule", "CANCEL_REPLACE_REJECTED"));
        List<Object> findings = at(r, "findings");
        assertEquals(18, findings.size());
        assertEquals("CRITICAL", at(findings, 0, "severity"));
        String at = at(findings, 0, "evidence", 0, "at");
        assertTrue(at.matches("day1\\.log:\\d+"), at);
        String excerpt = at(findings, 0, "evidence", 0, "excerpt");
        assertTrue(excerpt.startsWith("8=FIX.4.4|"), excerpt);
        assertFalse(stdout.contains("=ACC"), "account numbers must not appear in masked output");
        assertFalse(stdout.contains("TRDR-"), "party ids must not appear in masked output");
        assertTrue(stderr.isEmpty());
    }

    @Test
    void scanLimitAndFilters() {
        Map<String, Object> r = json("scan", LOG, "--limit", "3");
        assertEquals(3, ((List<?>) r.get("findings")).size());
        assertEquals(15L, r.get("omitted"));
        assertNotNull(r.get("hint"));

        r = json("scan", LOG, "--severity", "high");
        assertEquals(8L, (Long) at(r, "summary", "findings"));

        r = json("scan", LOG, "--session", "BROKERY-BUYSIDE1");
        assertEquals(1L, (Long) at(r, "summary", "findings"));
        assertEquals("SEQ_GAP", at(r, "findings", 0, "ruleId"));

        r = json("scan", LOG, "--rule", "overfill,no_ack");
        assertEquals(2L, (Long) at(r, "summary", "findings"));

        r = json("scan", LOG, "--since", "2026-01-05T10:00:00", "--until", "2026-01-05T10:05:00");
        assertEquals(4L, (Long) at(r, "summary", "findings"), stdout);
    }

    @Test
    void orderShowsTheWholeChainFromAnyIdInIt() {
        Map<String, Object> r = json("order", "ORD-000213", LOG);
        assertEquals(1L, r.get("matches"));
        assertEquals(List.of("ORD-000211", "ORD-000212", "ORD-000213", "ORD-000214"), at(r, "orders", 0, "clOrdIds"));
        List<Object> timeline = at(r, "orders", 0, "timeline");
        assertEquals(11, timeline.size());
        assertEquals("NewOrderSingle", at(timeline, 0, "msgType"));
        assertEquals("0 (Too late to cancel)", at(timeline, 9, "CxlRejReason"));
        assertEquals("1 (Partially filled)", at(timeline, 10, "OrdStatus"));
        assertEquals(2, ((List<?>) at(r, "orders", 0, "findings")).size());

        // the broker's OrderID finds the same order
        Map<String, Object> byOrderId = json("order", "BXO0000104", LOG);
        assertEquals(at(r, "orders", 0, "clOrdIds"), (Object) at(byOrderId, "orders", 0, "clOrdIds"));
    }

    @Test
    void orderTimelineIsCappedFromBothEnds() {
        Map<String, Object> r = json("order", "ORD-000213", LOG, "--limit", "4");
        assertEquals(4, ((List<?>) at(r, "orders", 0, "timeline")).size());
        assertEquals(7L, (Long) at(r, "orders", 0, "timelineOmitted"));
    }

    @Test
    void unknownOrderIsAnAnswerNotAnError() {
        Map<String, Object> r = json("order", "NOPE", LOG);
        assertEquals(0L, r.get("matches"));
        assertNotNull(r.get("hint"));
    }

    @Test
    void sessionHealth() {
        Map<String, Object> r = json("session", "BROKERY-BUYSIDE1", LOG);
        assertEquals("BUYSIDE1-BROKERY", r.get("session"));
        assertEquals("BROKEN", r.get("health"));
        assertEquals(3L, (Long) at(r, "directions", 1, "seqGaps", 0, "missing"));
        assertEquals(false, at(r, "directions", 1, "seqGaps", 0, "recovered"));
        assertEquals(1, ((List<?>) at(r, "directions", 1, "heartbeatSilences")).size());
        assertEquals(1, ((List<?>) at(r, "directions", 0, "unansweredTestRequests")).size());

        r = json("session", "BUYSIDE1-BROKERX", LOG);
        assertEquals("DEGRADED", r.get("health"), "a recovered gap degrades but does not break the session");

        r = json("session", "NOBODY-NOWHERE", LOG);
        assertEquals(false, r.get("found"));
        assertEquals(List.of("BUYSIDE1-BROKERX", "BUYSIDE1-BROKERY"), r.get("availableSessions"));
    }

    @Test
    void grepMatchesAllTermsAndDecodes() {
        Map<String, Object> r = json("grep", "35=8,39=8", LOG);
        assertEquals(1L, r.get("matches"));
        List<String> fields = at(r, "messages", 0, "fields");
        assertTrue(fields.contains("OrdStatus(39)=8 (Rejected)"), fields.toString());
        assertTrue(fields.contains("Account(1)=***"), fields.toString());

        r = json("grep", "MsgType=9", LOG, "--limit", "2");
        assertEquals(3L, r.get("matches"));
        assertEquals(2L, r.get("shown"));

        r = json("grep", "11=ORD-00021*,35=9", LOG);
        assertEquals(1L, r.get("matches"), "wildcard value");

        r = json("grep", "43", LOG);
        assertEquals(1L, r.get("matches"), "tag alone means present");

        assertEquals(2, run("grep", "NotATag=1", LOG));
    }

    @Test
    void stats() {
        Map<String, Object> r = json("stats", LOG);
        assertEquals(1072L, r.get("messages"));
        assertEquals(171L, (Long) at(r, "byMsgType", "NewOrderSingle (D)"));
        assertEquals(1L, (Long) at(r, "parseErrors", "badCheckSum"));
        assertEquals(1L, (Long) at(r, "parseErrors", "truncated"));
        assertEquals(2, ((List<?>) r.get("sessions")).size());
        assertEquals("2026-01-05T09:30:00Z", at(r, "timeRange", "first"));
    }

    @Test
    void explainTag() {
        Map<String, Object> r = json("explain-tag", "103", "3");
        assertEquals("OrdRejReason", r.get("name"));
        assertEquals("Order exceeds limit", r.get("meaning"));
        r = json("explain-tag", "OrdStatus");
        assertEquals("Filled", at(r, "values", "2"));
        r = json("explain-tag", "39=C");
        assertEquals("Expired", r.get("meaning"));
        r = json("explain-tag", "9999");
        assertEquals(false, r.get("known"));
    }

    @Test
    void customDictionaryExtendsExplainAndGrep(@TempDir Path dir) throws IOException {
        Path dict = dir.resolve("venue.json");
        Files.writeString(dict, "{\"tags\": {\"9999\": {\"name\": \"VenueCode\", \"values\": {\"7\": \"Auction\"}}}}");
        Map<String, Object> r = json("explain-tag", "VenueCode", "7", "--dict", dict.toString());
        assertEquals(9999L, r.get("tag"));
        assertEquals("Auction", r.get("meaning"));
        assertEquals(2, run("explain-tag", "1", "--dict", dir.resolve("missing.json").toString()));
    }

    @Test
    void noMaskWarnsAndShowsRawValues() {
        assertEquals(0, run("grep", "35=D", LOG, "--no-mask", "--limit", "1"));
        assertTrue(stderr.contains("WARNING: masking is OFF"), stderr);
        assertTrue(stdout.contains("\"masking\": \"OFF\""), stdout);
        assertTrue(stdout.contains("Account(1)=ACC"), stdout);
    }

    @Test
    void extraMaskTags() {
        assertEquals(0, run("grep", "35=D", LOG, "--mask-tags", "55,Price", "--limit", "1"));
        assertTrue(stdout.contains("Symbol(55)=***"), stdout);
        assertTrue(stdout.contains("Price(44)=***"), stdout);
    }

    @Test
    void textFormatIsReadable() {
        assertEquals(0, run("scan", LOG, "--format", "text", "--limit", "2"));
        assertTrue(stdout.contains("[CRITICAL] OVERFILL-1  BUYSIDE1-BROKERX  ORD-000131"), stdout);
        assertTrue(stdout.contains("byRule:"), stdout);
        assertFalse(stdout.trim().startsWith("{"));
    }

    @Test
    void usageErrors() {
        assertEquals(2, run("scan"));
        assertTrue(stdout.contains("\"error\""), stdout);
        assertEquals(2, run("scan", "no-such-file.log"));
        assertEquals(2, run("scan", LOG, "--bogus"));
        assertEquals(2, run("scan", LOG, "--severity", "LOUD"));
        assertEquals(2, run("scan", LOG, "--since", "yesterday"));
        assertEquals(2, run("frobnicate"));
        assertEquals(2, run("scan", LOG, "--format", "text", "--limit"));
        assertTrue(stderr.contains("needs a value") || stdout.contains("needs a value"));
        assertEquals(0, run("help"));
        assertTrue(stdout.contains("fixscan scan"));
        assertEquals(0, run());
        assertEquals(0, run("rules"));
        assertTrue(stdout.contains("INVALID_STATE_TRANSITION"));
    }

    @Test
    void gzipAndOtherDelimitersGiveTheSameFindings(@TempDir Path dir) throws IOException {
        Path gz = dir.resolve("day1.log.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(Files.readAllBytes(Path.of(LOG)));
        }
        assertEquals(18L, (Long) at(json("scan", gz.toString()), "summary", "findings"));
        Map<String, Object> order = json("order", "ORD-000018", gz.toString());
        assertEquals(2, ((List<?>) at(order, "orders", 0, "timeline")).size(), "evidence is re-read from gzip too");

        for (String delimiter : new String[] {"soh", "caret"}) {
            Path log = dir.resolve(delimiter + ".log");
            json("generate", "--out", log.toString(), "--expected", "none", "--delimiter", delimiter);
            Map<String, Object> r = json("scan", log.toString());
            assertEquals(18L, (Long) at(r, "summary", "findings"), delimiter);
            assertEquals(2L, (Long) at(r, "summary", "parseErrors"), delimiter);
        }
    }

    @Test
    void multipleFilesContinueTheSameSessions(@TempDir Path dir) throws IOException {
        List<String> lines = Files.readAllLines(Path.of(LOG), StandardCharsets.ISO_8859_1);
        Path a = dir.resolve("a.log"), b = dir.resolve("b.log");
        Files.write(a, lines.subList(0, 500), StandardCharsets.ISO_8859_1);
        Files.write(b, lines.subList(500, lines.size()), StandardCharsets.ISO_8859_1);
        Map<String, Object> r = json("scan", a.toString(), b.toString());
        assertEquals(18L, (Long) at(r, "summary", "findings"));
        assertTrue(stdout.contains("\"at\": \"b.log:"), "evidence points into the second file");
    }

    @Test
    void ackTimeoutOption() {
        Map<String, Object> r = json("scan", LOG, "--rule", "NO_ACK", "--ack-timeout", "3600");
        assertEquals(0L, (Long) at(r, "summary", "findings"), "the log is shorter than an hour");
    }
}
