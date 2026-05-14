package org.tachiyomi;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Profiler {
    private static final ConcurrentHashMap<String, AtomicLong> NET_TIME_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> NATIVE_NET_TIME_MS = new ConcurrentHashMap<>();

    private Profiler() {}

    public static void incrNet(String key, long timeMs) {
        NET_TIME_MS.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(timeMs);
    }

    public static void incrNativeNet(String key, long timeMs) {
        NATIVE_NET_TIME_MS.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(timeMs);
    }

    public static long getNet(String key) {
        AtomicLong value = NET_TIME_MS.get(key);
        return value != null ? value.get() : 0L;
    }

    public static long getNativeNet(String key) {
        AtomicLong value = NATIVE_NET_TIME_MS.get(key);
        return value != null ? value.get() : 0L;
    }
}
