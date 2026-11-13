package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrokenChainRuleTest extends RuleTest {
    @Test
    void cancelForUnknownOriginal() {
        cleanOrder("A1");
        fix.out("F", 11, "A9", 41, "GHOST", 55, "ACME", 54, 1);
        Finding f = single("BROKEN_CHAIN");
        assertEquals(Severity.MEDIUM, f.severity);
        assertEquals(List.of("GHOST", "A9"), f.clOrdIds);
        assertTrue(f.summary.contains("Cancel request A9 from BUYSIDE1 refers to OrigClOrdID(41)=GHOST"), f.summary);
        assertRules("BROKEN_CHAIN");
    }

    @Test
    void reportedOnceEvenWhenTheBrokerAnswers() {
        fix.out("G", 11, "A9", 41, "GHOST", 55, "ACME", 54, 1, 38, 10, 40, 2, 44, "1");
        fix.in("9", 37, "NONE", 11, "A9", 41, "GHOST", 39, 8, 434, 2, 102, 1, 58, "Unknown order");
        assertEquals(1, of("BROKEN_CHAIN").size());
        assertRules("BROKEN_CHAIN", "CANCEL_REPLACE_REJECTED");
    }

    @Test
    void intactChainIsNotReported() {
        fix.newOrder("A1", "ACME", 100, "10.00");
        fix.ack("A1", 100);
        fix.out("F", 11, "A2", 41, "A1", 55, "ACME", 54, 1);
        fix.exec("A2", "A1", '4', '4', 100, 0, 0, 0);
        assertNone("BROKEN_CHAIN");
    }
}
