package io.fixbreak.mask;

import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.Tags;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides client-identifying content before anything leaves the engine.
 *
 * <p>Always-masked tags: Account(1), ClientID(109), PartyID(448), plus any configured tags.
 * Text(58) is kept readable (reject reasons matter for diagnosis) but account-like and
 * name-like fragments inside it are replaced. Masking is best effort on free text; add
 * tag 58 to the configured tags to blank Text completely.
 */
public final class Masker {
    public static final String MASK = "***";

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    /** A cue word followed by something that looks like an identifier or a personal name. */
    private static final Pattern CUED = Pattern.compile(
            "\\b((?i:accounts?|acct|a/c|sub-?account|client|customer|trader|user(?:name|id)?|party|contact|attn|portfolio|book|mr|mrs|ms|dr)\\b\\.?"
                    + "(?:\\s*(?i:id|no|number|name|code))?\\s*[:=#(\\[]?\\s*)"
                    + "((?=[A-Za-z0-9_./-]*\\d)[A-Za-z0-9_./-]+|[A-Z][A-Z0-9_]{2,}|[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)");

    /** Mixed alphanumeric token with a long digit run (ACC12345), or a pure number of 9+ digits. */
    private static final Pattern ACCOUNT_LIKE = Pattern.compile(
            "\\b(?:[A-Za-z0-9_-]*[A-Za-z_-][A-Za-z0-9_-]*\\d{5,}[A-Za-z0-9_-]*|[A-Za-z0-9_-]*\\d{5,}[A-Za-z_-][A-Za-z0-9_-]*|\\d{9,})\\b");

    /** Words that follow a cue but are ordinary reject vocabulary, not identities. */
    private static final Set<String> NOT_IDENTITY = Set.of(
            "id", "order", "orders", "limit", "limits", "not", "unknown", "invalid", "is", "has", "was", "are",
            "error", "rejected", "reject", "blocked", "disabled", "suspended", "closed", "missing", "required",
            "restricted", "permission", "permissions", "credit", "risk", "check", "exceeded", "exceeds", "type",
            "status", "level", "for", "on", "in", "of", "the", "and", "or", "to", "by", "with");

    private final boolean enabled;
    private final Set<Integer> maskedTags = new HashSet<>(Set.of(Tags.ACCOUNT, Tags.CLIENT_ID, Tags.PARTY_ID));

    public Masker(boolean enabled, Set<Integer> extraTags) {
        this.enabled = enabled;
        if (extraTags != null) maskedTags.addAll(extraTags);
    }

    public static Masker on() {
        return new Masker(true, null);
    }

    public static Masker off() {
        return new Masker(false, null);
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<Integer> maskedTags() {
        return Set.copyOf(maskedTags);
    }

    /** Value of one field as it may be shown to a user or the model. */
    public String value(int tag, String value, FixMessage context) {
        if (!enabled || value == null) return value;
        if (maskedTags.contains(tag)) return MASK;
        if (tag == Tags.TEXT) return text(value, context);
        return value;
    }

    /** Masks account-like and name-like fragments of free text, keeping the order's own identifiers readable. */
    public String text(String text, FixMessage context) {
        if (!enabled || text == null || text.isEmpty()) return text;
        if (maskedTags.contains(Tags.TEXT)) return MASK;

        String out = EMAIL.matcher(text).replaceAll(MASK);

        Matcher m = CUED.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String candidate = m.group(2);
            String firstWord = candidate.split("\\s+")[0].toLowerCase(Locale.ROOT);
            boolean keep = NOT_IDENTITY.contains(firstWord) || isOrderIdentifier(candidate, context);
            m.appendReplacement(sb, Matcher.quoteReplacement(keep ? m.group() : m.group(1) + MASK));
        }
        m.appendTail(sb);
        out = sb.toString();

        m = ACCOUNT_LIKE.matcher(out);
        sb = new StringBuilder();
        while (m.find()) {
            boolean keep = isOrderIdentifier(m.group(), context);
            m.appendReplacement(sb, Matcher.quoteReplacement(keep ? m.group() : MASK));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Order identifiers already visible in the same message are not secrets and help the investigation. */
    private static boolean isOrderIdentifier(String token, FixMessage ctx) {
        if (ctx == null) return false;
        return token.equals(ctx.get(Tags.CL_ORD_ID)) || token.equals(ctx.get(Tags.ORIG_CL_ORD_ID))
                || token.equals(ctx.get(Tags.ORDER_ID)) || token.equals(ctx.get(Tags.EXEC_ID))
                || token.equals(ctx.get(Tags.BUSINESS_REJECT_REF_ID));
    }

    /** The message rendered as {@code tag=value|tag=value|...} with masking applied, cut to {@code maxLen} chars. */
    public String excerpt(FixMessage msg, int maxLen) {
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < msg.fieldCount(); i++) {
            int tag = msg.tagAt(i);
            sb.append(tag).append('=').append(value(tag, display(msg.valueAt(i)), msg)).append('|');
            if (sb.length() > maxLen) break;
        }
        if (sb.length() > maxLen) {
            sb.setLength(Math.max(0, maxLen - 3));
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Log lines are read as ISO-8859-1 so checksums stay exact; for display, turn multi-byte
     * UTF-8 sequences back into the characters the sender meant.
     */
    public static String display(String latin1) {
        for (int i = 0; i < latin1.length(); i++) {
            if (latin1.charAt(i) >= 0x80) {
                return new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            }
        }
        return latin1;
    }
}
