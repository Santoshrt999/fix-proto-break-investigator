package io.fixbreak.gen;

/**
 * Builds a well-formed FIX message with correct BodyLength(9) and CheckSum(10).
 * Used by the synthetic data generator and by tests.
 */
public final class FixBuilder {
    public static final char SOH = '\u0001';

    private final String beginString;
    private final StringBuilder body = new StringBuilder(256);

    public FixBuilder(String beginString, String msgType) {
        this.beginString = beginString;
        add(35, msgType);
    }

    public FixBuilder add(int tag, String value) {
        body.append(tag).append('=').append(value).append(SOH);
        return this;
    }

    public FixBuilder add(int tag, long value) {
        return add(tag, Long.toString(value));
    }

    public FixBuilder add(int tag, char value) {
        return add(tag, String.valueOf(value));
    }

    /** The message with SOH delimiters. */
    public String build() {
        StringBuilder sb = new StringBuilder(body.length() + 32);
        sb.append("8=").append(beginString).append(SOH);
        sb.append("9=").append(body.length()).append(SOH);
        sb.append(body);
        int sum = 0;
        for (int i = 0; i < sb.length(); i++) sum += sb.charAt(i);
        sb.append("10=").append(String.format("%03d", sum & 0xFF)).append(SOH);
        return sb.toString();
    }

    /** The message with SOH replaced by another delimiter, as many log viewers and loggers do. */
    public String build(String delimiter) {
        return build().replace(String.valueOf(SOH), delimiter);
    }
}
