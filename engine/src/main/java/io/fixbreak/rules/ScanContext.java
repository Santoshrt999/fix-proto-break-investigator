package io.fixbreak.rules;

import io.fixbreak.dict.FixDictionary;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.mask.Masker;
import io.fixbreak.order.OrderState;
import io.fixbreak.session.SessionState;

import java.util.List;

/** The whole picture at end of log, for rules that need to look back. */
public record ScanContext(ScanConfig config, List<String> fileNames, List<SessionState> sessions,
                          List<OrderState> orders, long endTime) {
    public FixDictionary dict() {
        return config.dict;
    }

    public Masker masker() {
        return config.masker;
    }
}
