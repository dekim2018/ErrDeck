package com.errdeck.worker.app;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 이슈별 분당 원본 저장 상한(10.3, adr/0004). 같은 예외 1만 건의 원본을 전부 둘 이유는 없지만 건수는 모두 남긴다.
 *
 * <p>분은 worker 가 처리한 시각으로 나눈다. SDK 의 시계가 틀려도 상한이 흔들리지 않게 하기 위해서다.
 * worker 마다 따로 세므로 worker 가 여러 대면 상한도 그 배수가 된다. 쓰기 스레드 하나만 쓴다.
 */
final class SampleLimiter {

    private final int perMinute;
    private long minute = Long.MIN_VALUE;
    private final Map<Long, Integer> counts = new HashMap<>();

    SampleLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    /** 이 이슈의 원본을 하나 더 저장해도 되는지. 된다면 센다. */
    boolean allow(long issueId, Instant now) {
        long current = now.getEpochSecond() / 60;
        if (current != minute) {
            minute = current;
            counts.clear();
        }
        int used = counts.getOrDefault(issueId, 0);
        if (used >= perMinute) {
            return false;
        }
        counts.put(issueId, used + 1);
        return true;
    }
}
