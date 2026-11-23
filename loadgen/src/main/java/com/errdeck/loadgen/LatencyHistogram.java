package com.errdeck.loadgen;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 응답 시간 분포. 값마다 저장하지 않고 칸에 세어 두므로 오래 돌려도 메모리가 늘지 않는다.
 *
 * <p>1,024µs 아래는 1µs 단위, 그 위는 2의 거듭제곱 구간마다 1,024칸으로 나눈다. 상대 오차는 0.1% 이하다.
 * 여러 스레드가 동시에 기록한다.
 */
public final class LatencyHistogram {

    private static final int SUB_BUCKETS = 1024;
    private static final int SUB_BITS = 10;
    /** 2^40µs(약 12일)까지 담는다. */
    private static final int MAX_EXPONENT = 40;

    private final AtomicLongArray counts = new AtomicLongArray(SUB_BUCKETS * (MAX_EXPONENT - SUB_BITS + 2));

    public void record(long micros) {
        counts.incrementAndGet(index(Math.max(0, micros)));
    }

    public long count() {
        long total = 0;
        for (int i = 0; i < counts.length(); i++) {
            total += counts.get(i);
        }
        return total;
    }

    /** @param quantile 0과 1 사이. 0.99 는 p99. 기록이 없으면 0 */
    public long percentile(double quantile) {
        long total = count();
        if (total == 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(quantile * total));
        long seen = 0;
        for (int i = 0; i < counts.length(); i++) {
            seen += counts.get(i);
            if (seen >= rank) {
                return upperBound(i);
            }
        }
        return upperBound(counts.length() - 1);
    }

    public long max() {
        for (int i = counts.length() - 1; i >= 0; i--) {
            if (counts.get(i) > 0) {
                return upperBound(i);
            }
        }
        return 0;
    }

    static int index(long value) {
        if (value < SUB_BUCKETS) {
            return (int) value;
        }
        int exponent = Math.min(63 - Long.numberOfLeadingZeros(value), MAX_EXPONENT);
        // 맨 앞 1을 포함한 위쪽 11비트로 칸을 고른다. 아래 비트는 버리므로 칸 너비는 값의 1/1024 이하다.
        int shift = exponent - SUB_BITS;
        int sub = (int) Math.min(value >>> shift, 2L * SUB_BUCKETS - 1) - SUB_BUCKETS;
        return SUB_BUCKETS * (shift + 1) + sub;
    }

    /** 칸에 들어가는 가장 큰 값. 분위수를 낮게 보고하지 않으려고 칸의 위쪽 끝을 쓴다. */
    static long upperBound(int index) {
        if (index < SUB_BUCKETS) {
            return index;
        }
        int group = index / SUB_BUCKETS;
        int sub = index % SUB_BUCKETS;
        int shift = group - 1;
        return ((long) (SUB_BUCKETS + sub + 1) << shift) - 1;
    }
}
