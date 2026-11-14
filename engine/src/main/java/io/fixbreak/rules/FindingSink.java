package io.fixbreak.rules;

import io.fixbreak.order.OrderState;
import io.fixbreak.session.SessionState;

/** Where rules report what they find. */
public interface FindingSink {
    /**
     * @param session   the session involved, or null
     * @param order     the order involved, or null for session-level findings
     * @param firstTime when the story starts (e.g. the order was sent); may be {@code Times.UNKNOWN}
     * @param lastTime  when the break was observed
     * @param summary   one line, already masked
     * @param evidence  line references, most relevant first; zeros and repeats are dropped, at most five are kept
     */
    void report(Rule rule, Severity severity, SessionState session, OrderState order,
                long firstTime, long lastTime, String summary, long... evidence);
}
