package io.fixbreak.engine;

import io.fixbreak.dict.FixDictionary;
import io.fixbreak.mask.Masker;
import io.fixbreak.rules.Severity;
import io.fixbreak.util.Times;

import java.util.Set;

/** Settings for one scan. */
public final class ScanConfig {
    public FixDictionary dict = FixDictionary.builtin();
    public Masker masker = Masker.on();

    /** NO_ACK: how long a NewOrderSingle may wait for its first ExecutionReport. */
    public long ackTimeoutMillis = 30_000;

    // Finding filters. Counts and the stored findings both respect them.
    /** Only findings on this session (either direction order), or null for all. */
    public String sessionFilter;
    public Severity minSeverity = Severity.INFO;
    /** Only these rule ids, or null for all. */
    public Set<String> ruleFilter;
    /** Only findings whose time span overlaps [since, until]. */
    public long since = Times.UNKNOWN;
    public long until = Times.UNKNOWN;

    /** Findings kept in memory per rule; anything beyond is still counted. */
    public int maxStoredPerRule = 5000;
}
