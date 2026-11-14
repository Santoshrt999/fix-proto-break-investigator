package io.fixbreak.rules;

import io.fixbreak.parser.ParseIssue;

/**
 * One break detector. To add a detector, implement this interface in its own class and
 * register it in {@link Rules#defaults()}.
 *
 * <p>Rules are stateless where possible: the session tracker and order book have already
 * worked out what each message did, and expose it through the context objects. A rule only
 * decides whether that is a break and words the finding.
 */
public interface Rule {
    /** Stable identifier in UPPER_SNAKE_CASE, e.g. {@code ORDER_REJECTED}. */
    String id();

    /** One sentence for documentation and {@code fixscan rules}. */
    String description();

    /** Called for every complete message, after session and order state have been updated. */
    default void onMessage(MessageContext ctx, FindingSink out) {}

    /** Called for every line the parser could not fully validate. */
    default void onParseIssue(ParseIssue issue, FindingSink out) {}

    /** Called once after the last line, for breaks that are only visible at end of log. */
    default void onEnd(ScanContext ctx, FindingSink out) {}
}
