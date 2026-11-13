package io.fixbreak.rules;

/** How urgently a finding deserves attention. Ordered most severe first. */
public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW, INFO;

    public boolean atLeast(Severity min) {
        return ordinal() <= min.ordinal();
    }

    public static Severity parse(String s) {
        return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
