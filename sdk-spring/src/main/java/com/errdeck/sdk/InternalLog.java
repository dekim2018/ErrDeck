package com.errdeck.sdk;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SDK 자신의 문제를 남기는 로그. 같은 문제는 1분에 한 번만 남긴다. ingest 가 죽어 있을 때 요청마다 로그를 쓰면
 * 감시 대상의 로그를 SDK 가 덮어 버린다. 로그 어펜더는 이 이름의 로그만 정확히 건너뛴다.
 */
public final class InternalLog {

    public static final String NAME = "com.errdeck.sdk.internal";
    private static final System.Logger LOG = System.getLogger(NAME);
    private static final long INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private InternalLog() {
    }

    public static void warn(String key, String message) {
        long now = System.nanoTime();
        Long last = LAST.get(key);
        if (last != null && now - last < INTERVAL_NANOS) {
            return;
        }
        LAST.put(key, now);
        try {
            LOG.log(Level.WARNING, "ErrDeck: " + message);
        } catch (RuntimeException ignored) {
            // 로그조차 남기지 못해도 감시 대상에 예외를 던지지 않는다.
        }
    }
}
