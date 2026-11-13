package io.fixbreak.rules;

import io.fixbreak.dict.FixDictionary;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.mask.Masker;
import io.fixbreak.order.OrderUpdate;
import io.fixbreak.parser.FixMessage;
import io.fixbreak.parser.Tags;
import io.fixbreak.session.DirectionState;
import io.fixbreak.session.SeqGap;
import io.fixbreak.session.SessionState;

/** Everything a rule may look at for one message. Reused between messages: do not keep references. */
public final class MessageContext {
    public final ScanConfig config;
    public final FixDictionary dict;
    public final Masker masker;

    public FixMessage msg;
    /** MsgType(35), never null. */
    public String msgType;
    public SessionState session;
    public DirectionState direction;
    /** A sequence gap revealed by this message, or null. */
    public SeqGap newGap;
    /** What the message did to an order; null for messages that are not order-related. */
    public OrderUpdate update;

    public MessageContext(ScanConfig config) {
        this.config = config;
        this.dict = config.dict;
        this.masker = config.masker;
    }

    /** "3 (Order exceeds limit)" for an enum tag on this message, or null when absent. */
    public String describe(int tag) {
        return dict.describe(tag, msg.get(tag));
    }

    /** {@code ; Text(58)="..."} with masking applied, or an empty string when the message has no text. */
    public String textSuffix() {
        String t = msg.get(Tags.TEXT);
        if (t == null || t.isEmpty()) return "";
        return "; Text(58)=\"" + masker.text(Masker.display(t), msg) + "\"";
    }
}
