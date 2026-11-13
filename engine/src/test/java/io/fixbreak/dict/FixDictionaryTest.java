package io.fixbreak.dict;

import io.fixbreak.util.Json;
import io.fixbreak.util.Times;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FixDictionaryTest {

    @Test
    void builtinCoversTheTagsTheRulesUse() {
        FixDictionary d = FixDictionary.builtin();
        assertEquals("MsgType", d.tagName(35));
        assertEquals("ExecutionReport", d.valueName(35, "8"));
        assertEquals("BusinessMessageReject", d.valueName(35, "j"));
        assertEquals("Rejected", d.valueName(39, "8"));
        assertEquals("Order exceeds limit", d.valueName(103, "3"));
        assertEquals("Too late to cancel", d.valueName(102, "0"));
        assertEquals("Required tag missing", d.valueName(373, "1"));
        assertEquals("Application not available", d.valueName(380, "4"));
        assertEquals("Buy", d.valueName(54, "1"));
        assertEquals("Limit", d.valueName(40, "2"));
        assertNotNull(d.valueName(59, "3"));
        assertNotNull(d.valueName(150, "F"));
        assertEquals("8 (Rejected)", d.describe(39, "8"));
        assertEquals("Z", d.describe(39, "Z"));
        assertNull(d.tagName(99999));
    }

    @Test
    void resolvesTagsByNumberOrName() {
        FixDictionary d = FixDictionary.builtin();
        assertEquals(39, d.resolveTag("39"));
        assertEquals(39, d.resolveTag("ordstatus"));
        assertEquals(11, d.resolveTag("ClOrdID"));
        assertNull(d.resolveTag("NoSuchTag"));
    }

    @Test
    void overlayAddsCustomTagsAndOverridesValues(@TempDir Path dir) throws IOException {
        Path custom = dir.resolve("custom.json");
        Files.writeString(custom, """
                {"tags": {
                  "5001": {"name": "VenueRejectCode", "values": {"17": "Self-match prevention"}},
                  "103":  {"values": {"99": "Other (venue specific)", "100": "Kill switch active"}}
                }}""");
        FixDictionary d = FixDictionary.builtin();
        d.overlay(custom);
        assertEquals("VenueRejectCode", d.tagName(5001));
        assertEquals("Self-match prevention", d.valueName(5001, "17"));
        assertEquals("Kill switch active", d.valueName(103, "100"));
        assertEquals("Other (venue specific)", d.valueName(103, "99"));
        assertEquals("Unknown symbol", d.valueName(103, "1"), "built-in values survive an overlay");
        assertEquals("OrdRejReason", d.tagName(103));
    }

    @Test
    void badOverlayGivesAClearError(@TempDir Path dir) throws IOException {
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"tags\": {\"abc\": {}}}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FixDictionary.builtin().overlay(bad));
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    void jsonRoundTrip() {
        String text = "{\"a\": [1, 2.5, \"x\\n\\\"y\\\"\", true, null], \"b\": {\"c\": []}}";
        Object parsed = Json.parse(text);
        assertEquals(Map.of("c", List.of()), ((Map<?, ?>) parsed).get("b"));
        assertEquals(parsed, Json.parse(Json.write(parsed)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": }"));
    }

    @Test
    void timestamps() {
        long t = Times.parseFix("20260105-09:30:01.250", 0, 21);
        assertEquals("2026-01-05T09:30:01.250Z", Times.iso(t));
        assertEquals("20260105-09:30:01.250", Times.fix(t));
        assertEquals("2026-01-05 09:30:01.250", Times.logPrefix(t));
        assertEquals(t, Times.parseLoose("2026-01-05T09:30:01.250Z"));
        assertEquals(t, Times.parseLoose("2026-01-05 09:30:01.250"));
        assertEquals(t - 250, Times.parseLoose("20260105-09:30:01"));
        assertEquals(Times.UNKNOWN, Times.parseLoose("yesterday"));
        long leap = Times.parseLoose("2024-02-29T23:59:59");
        assertEquals("20240229-23:59:59.000", Times.fix(leap));
        assertEquals("19700101-00:00:00.000", Times.fix(0));
    }
}
