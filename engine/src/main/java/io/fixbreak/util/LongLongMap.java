package io.fixbreak.util;

/**
 * Open-addressing {@code long -> long} map with no per-entry objects. Used to remember
 * millions of ExecID hashes cheaply. Key 0 and value 0 are reserved ("absent").
 */
public final class LongLongMap {
    private long[] keys;
    private long[] vals;
    private int size;
    private int mask;

    public LongLongMap() {
        this(1024);
    }

    public LongLongMap(int capacity) {
        int cap = Integer.highestOneBit(Math.max(16, capacity) * 2 - 1);
        keys = new long[cap];
        vals = new long[cap];
        mask = cap - 1;
    }

    public int size() {
        return size;
    }

    /** Returns the value for {@code key}, or 0 if absent. */
    public long get(long key) {
        if (key == 0) key = 1;
        int i = slot(key);
        while (keys[i] != 0) {
            if (keys[i] == key) return vals[i];
            i = (i + 1) & mask;
        }
        return 0;
    }

    /** Stores {@code value} unless the key is already present. Returns the previous value, or 0 if newly added. */
    public long putIfAbsent(long key, long value) {
        if (key == 0) key = 1;
        int i = slot(key);
        while (keys[i] != 0) {
            if (keys[i] == key) return vals[i];
            i = (i + 1) & mask;
        }
        keys[i] = key;
        vals[i] = value;
        if (++size * 2 > keys.length) grow();
        return 0;
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }

    private void grow() {
        long[] ok = keys, ov = vals;
        keys = new long[ok.length * 2];
        vals = new long[ok.length * 2];
        mask = keys.length - 1;
        for (int j = 0; j < ok.length; j++) {
            if (ok[j] == 0) continue;
            int i = slot(ok[j]);
            while (keys[i] != 0) i = (i + 1) & mask;
            keys[i] = ok[j];
            vals[i] = ov[j];
        }
    }

    /** 64-bit FNV-1a hash of a string with a final avalanche step. */
    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }
}
