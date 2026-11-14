package io.fixbreak.mask;

import io.fixbreak.gen.FixBuilder;
import io.fixbreak.parser.FixLineParser;
import io.fixbreak.parser.FixMessage;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MaskerTest {
    private final Masker masker = Masker.on();

    private static FixMessage parse(FixBuilder b) {
        FixLineParser.Result r = new FixLineParser.Result();
        assertTrue(new FixLineParser().parse(b.build("|"), 0, 1, r));
        return r.msg;
    }

    private static FixBuilder reject(String text) {
        return new FixBuilder("FIX.4.4", "8").add(49, "BROKERX").add(56, "BUYSIDE1").add(34, 5)
                .add(11, "ORD-000123").add(37, "BX7700001").add(1, "ACC90210").add(109, "CLIENT-ALPHA")
                .add(453, 1).add(448, "JSMITH").add(447, 'D').add(452, 12)
                .add(150, '8').add(39, '8').add(103, 3).add(58, text);
    }

    @Test
    void masksIdentityTagsByDefault() {
        String ex = masker.excerpt(parse(reject("Order exceeds limit")), 1000);
        assertTrue(ex.contains("|1=***|"), ex);
        assertTrue(ex.contains("|109=***|"), ex);
        assertTrue(ex.contains("|448=***|"), ex);
        assertFalse(ex.contains("ACC90210"), ex);
        assertFalse(ex.contains("CLIENT-ALPHA"), ex);
        assertFalse(ex.contains("JSMITH"), ex);
        // everything needed for diagnosis is still there
        assertTrue(ex.contains("|11=ORD-000123|"), ex);
        assertTrue(ex.contains("|103=3|"), ex);
        assertTrue(ex.contains("58=Order exceeds limit|"), ex);
    }

    @Test
    void masksAccountLikeFragmentsInText() {
        assertEquals("Order exceeds limit for account ***",
                masker.text("Order exceeds limit for account ACC90210", null));
        assertEquals("Unknown acct: *** on book ***",
                masker.text("Unknown acct: 7781 on book EQ_DESK_3", null));
        assertEquals("Credit check failed ***", masker.text("Credit check failed XK55120099", null));
        assertEquals("ref *** not eligible", masker.text("ref 1234567890 not eligible", null));
    }

    @Test
    void masksNameLikeFragmentsInText() {
        assertEquals("Rejected by trader *** per desk policy",
                masker.text("Rejected by trader John Smith per desk policy", null));
        assertEquals("Contact: *** for release", masker.text("Contact: jane.doe@example.com for release", null));
        assertEquals("Call Mr. *** at the desk", masker.text("Call Mr. Okafor at the desk", null));
        assertEquals("client *** not permissioned", masker.text("client HEDGECO not permissioned", null));
    }

    @Test
    void keepsOrdinaryRejectReasonsReadable() {
        for (String text : new String[] {
                "Unknown symbol ZZZZ",
                "Order exceeds limit: notional 12500000 > max 10000000",
                "Too late to cancel",
                "Price 10.55 outside band 9.50-10.50",
                "Insufficient Buying Power",
                "Account not found",
                "Unknown account",
                "Client order id is required",
                "Trader not permissioned for symbol",
                "Market Closed",
        }) {
            assertEquals(text, masker.text(text, null));
        }
    }

    @Test
    void keepsTheMessagesOwnOrderIdentifiers() {
        FixMessage m = parse(reject("Order ORD-000123 (BX7700001) rejected, see ORD-000999"));
        assertEquals("Order ORD-000123 (BX7700001) rejected, see ***", masker.value(58, m.get(58), m));
    }

    @Test
    void configuredTagsAreMaskedIncludingWholeText() {
        Masker custom = new Masker(true, Set.of(58, 50));
        FixMessage m = parse(reject("Unknown symbol ZZZZ").add(50, "DESK-7"));
        String ex = custom.excerpt(m, 1000);
        assertTrue(ex.contains("|58=***|"), ex);
        assertTrue(ex.contains("|50=***|"), ex);
    }

    @Test
    void noMaskLeavesEverythingAlone() {
        Masker off = Masker.off();
        assertFalse(off.enabled());
        String ex = off.excerpt(parse(reject("for account ACC90210")), 1000);
        assertTrue(ex.contains("|1=ACC90210|"), ex);
        assertTrue(ex.contains("58=for account ACC90210|"), ex);
    }

    @Test
    void excerptIsCapped() {
        String ex = masker.excerpt(parse(reject("x".repeat(500))), 120);
        assertEquals(120, ex.length());
        assertTrue(ex.endsWith("..."));
    }

    @Test
    void masksIdentitiesTheReviewFoundLeaking() {
        String[][] cases = {
                {"Unknown ClientID HEDGEFUND_A", "HEDGEFUND_A"},
                {"account owner Jane Doe", "Jane Doe"},
                {"call +1 212 555 0199 about it", "555 0199"},
                {"user jdoe logged out", "jdoe"},
                {"limit hit for SMITHJ / 9876543", "9876543"},
        };
        for (String[] c : cases) {
            String out = masker.text(c[0], null);
            assertFalse(out.contains(c[1]), c[0] + " -> " + out);
        }
        assertEquals("user not found", masker.text("user not found", null));
        assertEquals("Order exceeds limit: notional 15500000.00 > max 10000000.00",
                masker.text("Order exceeds limit: notional 15500000.00 > max 10000000.00", null));
    }

    @Test
    void otherAccountAndPartyTagsAreMaskedByDefault() {
        for (int tag : new int[] {79, 440, 523, 524, 355}) {
            assertEquals(Masker.MASK, masker.value(tag, "ALLOC12345678", null), "tag " + tag);
        }
    }
}
