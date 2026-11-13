package io.fixbreak.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateExecRuleTest extends RuleTest {
    @Test
    void sameExecIdWithoutPossDup() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.ack("A1", 1000);
        fix.execWithId("X77", "A1", null, 'F', '1', 1000, 300, 300, 700);
        fix.execWithId("X77", "A1", null, 'F', '1', 1000, 300, 300, 700);
        fix.exec("A1", null, 'F', '2', 1000, 700, 1000, 0);
        Finding f = single("DUPLICATE_EXEC");
        assertEquals(Severity.HIGH, f.severity);
        assertTrue(f.summary.contains("ExecID(17)=X77"), f.summary);
        assertTrue(f.summary.contains("LastQty(32)=300 could be booked twice"), f.summary);
        assertEquals(2, f.evidence.length, "both occurrences");
        // the repeat is not applied, so quantities still reconcile
        assertRules("DUPLICATE_EXEC");
    }

    @Test
    void possDupAndPossResendAreNotDuplicates() {
        fix.newOrder("A1", "ACME", 1000, "10.00");
        fix.execWithId("X1", "A1", null, 'F', '1', 1000, 300, 300, 700);
        fix.execWithId("X1", "A1", null, 'F', '1', 1000, 300, 300, 700, 97, "Y");
        fix.sendSeq("BROKERX", "BUYSIDE1", 1, "8", 43, "Y", 37, "BX-1", 11, "A1", 17, "X1", 150, "F", 39, 1, 38, 1000, 32, 300, 151, 700, 14, 300, 6, "10");
        fix.exec("A1", null, 'F', '2', 1000, 700, 1000, 0);
        assertTrue(scan().isEmpty());
    }

    @Test
    void sameExecIdOnDifferentSessionsIsFine() {
        fix.send("C1", "B1", "D", 11, "1", 55, "ACME", 54, 1, 38, 10, 40, 1);
        fix.send("B1", "C1", "8", 37, "O1", 11, "1", 17, "E1", 150, "F", 39, 2, 38, 10, 32, 10, 151, 0, 14, 10, 6, 1);
        fix.send("C2", "B2", "D", 11, "1", 55, "ACME", 54, 1, 38, 10, 40, 1);
        fix.send("B2", "C2", "8", 37, "O1", 11, "1", 17, "E1", 150, "F", 39, 2, 38, 10, 32, 10, 151, 0, 14, 10, 6, 1);
        assertTrue(scan().isEmpty());
    }
}
