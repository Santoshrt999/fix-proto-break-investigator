package io.fixbreak.parser;

import io.fixbreak.gen.FixBuilder;
import io.fixbreak.parser.FixLineParser.Issue;
import io.fixbreak.util.Times;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class FixLineParserTest {
    private final FixLineParser parser = new FixLineParser();
    private final FixLineParser.Result r = new FixLineParser.Result();

    private static FixBuilder order() {
        return new FixBuilder("FIX.4.4", "D")
                .add(49, "BUYSIDE1").add(56, "BROKERX").add(34, 12).add(52, "20260105-09:30:01.250")
                .add(11, "ORD-1").add(55, "ACME").add(54, '1').add(38, 100).add(40, '2').add(44, "10.50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0001", "|", "^A"})
    void parsesEveryDelimiter(String delim) {
        assertTrue(parser.parse(order().build(delim), 0, 7, r));
        assertNull(r.issue, () -> r.detail);
        assertTrue(r.complete);
        assertEquals("D", r.msg.msgType());
        assertEquals("ORD-1", r.msg.get(11));
        assertEquals(100.0, r.msg.getDouble(38));
        assertEquals(10.5, r.msg.getDouble(44));
        assertEquals(12, r.msg.getLong(34, -1));
        assertEquals('1', r.msg.getChar(54));
        assertNull(r.msg.get(999));
        assertEquals(7, r.msg.line());
    }

    @Test
    void stripsLogPrefixes() {
        String msg = order().build("|");
        for (String prefix : new String[] {
                "2026-01-05 09:30:01.250 OUT ",
                "2026-01-05T09:30:01,250Z [main] INFO  fix.out - ",
                "20260105-09:30:01.250: ",
                "<20260105-09:30:01, FIX.4.4:BUYSIDE1->BROKERX, outgoing> (",
        }) {
            assertTrue(parser.parse(prefix + msg, 0, 1, r), prefix);
            assertNull(r.issue, prefix);
            assertEquals("ORD-1", r.msg.get(11));
        }
    }

    @Test
    void usesSendingTimeThenPrefixTimestamp() {
        assertTrue(parser.parse(order().build("|"), 0, 1, r));
        assertEquals("2026-01-05T09:30:01.250Z", Times.iso(r.msg.time()));

        String noSendingTime = new FixBuilder("FIX.4.2", "0").add(49, "A").add(56, "B").add(34, 1).build("|");
        assertTrue(parser.parse("2026-01-05 10:00:00.500 IN " + noSendingTime, 0, 1, r));
        assertEquals("2026-01-05T10:00:00.500Z", Times.iso(r.msg.time()));
    }

    @Test
    void nonFixLinesAreIgnored() {
        assertFalse(parser.parse("", 0, 1, r));
        assertFalse(parser.parse("2026-01-05 09:30:00 INFO Session started HeartBtInt108=FIX", 0, 1, r));
        assertFalse(parser.parse("some application log line", 0, 1, r));
    }

    @Test
    void badChecksumIsReportedNotThrown() {
        String good = order().build("|");
        String bad = good.replaceAll("10=\\d{3}", "10=000");
        if (bad.equals(good)) bad = good.replaceAll("10=\\d{3}", "10=001");
        assertTrue(parser.parse(bad, 0, 1, r));
        assertEquals(Issue.BAD_CHECKSUM, r.issue);
        assertTrue(r.complete, "a bad checksum still leaves a usable message");
        assertEquals("ORD-1", r.msg.get(11));
    }

    @Test
    void badBodyLengthIsReported() {
        String bad = order().build("|").replaceFirst("\\|9=\\d+\\|", "|9=5|");
        assertTrue(parser.parse(bad, 0, 1, r));
        assertEquals(Issue.BAD_BODY_LENGTH, r.issue);
        assertTrue(r.complete);
    }

    @Test
    void editedFieldBreaksIntegrity() {
        String tampered = order().build("|").replace("38=100", "38=900000");
        assertTrue(parser.parse(tampered, 0, 1, r));
        assertEquals(Issue.BAD_BODY_LENGTH, r.issue);
    }

    @Test
    void truncatedLinesAreReported() {
        String full = order().build("|");
        for (int cut : new int[] {12, 40, full.indexOf("55=") + 1, full.indexOf("44=") + 5, full.indexOf("10=")}) {
            String line = full.substring(0, cut);
            assertTrue(parser.parse(line, 0, 1, r), line);
            assertEquals(Issue.TRUNCATED, r.issue, line);
            assertFalse(r.complete, line);
        }
        // header fields before the cut are still readable
        assertTrue(parser.parse(full.substring(0, full.indexOf("55=") + 1), 0, 1, r));
        assertEquals(12, r.msg.getLong(34, -1));
        assertEquals("ORD-1", r.msg.get(11));
        assertNull(r.msg.get(55));
    }

    @Test
    void unknownDelimiterIsMalformed() {
        assertTrue(parser.parse("8=FIX.4.4 9=10 35=0 10=000", 0, 1, r));
        assertEquals(Issue.MALFORMED, r.issue);
    }

    @Test
    void missingMsgTypeIsMalformed() {
        assertTrue(parser.parse("8=FIX.4.4|9=5|49=A|10=000|", 0, 1, r));
        assertEquals(Issue.MALFORMED, r.issue);
        assertFalse(r.complete);
    }

    @Test
    void pipeInsideTextDoesNotSplitTheField() {
        String msg = new FixBuilder("FIX.4.4", "3").add(49, "A").add(56, "B").add(34, 2)
                .add(58, "bad value | see docs").build("|");
        assertTrue(parser.parse(msg, 0, 1, r));
        assertEquals("bad value | see docs", r.msg.get(58));
    }

    @Test
    void handlesFixt11AndManyFields() {
        FixBuilder b = new FixBuilder("FIXT.1.1", "8").add(49, "A").add(56, "B").add(34, 3);
        for (int i = 0; i < 100; i++) b.add(5000 + i, "v" + i);
        assertTrue(parser.parse(b.build(), 0, 1, r));
        assertNull(r.issue, () -> r.detail);
        assertEquals("FIXT.1.1", r.msg.get(8));
        assertEquals("v99", r.msg.get(5099));
    }
}
