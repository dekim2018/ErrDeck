package com.errdeck.loadgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

    @Test
    void 기록이_없으면_분위수는_0이다() {
        LatencyHistogram histogram = new LatencyHistogram();

        assertEquals(0, histogram.percentile(0.99));
        assertEquals(0, histogram.max());
    }

    @Test
    void 값_1부터_10000까지_고르게_기록하면_p50과_p99가_오차_0점1퍼센트_안이다() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 1; i <= 10_000; i++) {
            histogram.record(i);
        }

        assertEquals(10_000, histogram.count());
        assertWithin(5_000, histogram.percentile(0.50));
        assertWithin(9_900, histogram.percentile(0.99));
        assertWithin(10_000, histogram.max());
    }

    @Test
    void 분위수를_실제보다_낮게_보고하지_않는다() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(123_456_789);

        assertTrue(histogram.percentile(0.5) >= 123_456_789);
    }

    @Test
    void 칸_번호와_칸의_위쪽_끝이_맞물린다() {
        for (long value : new long[] {0, 1, 1023, 1024, 1025, 2047, 2048, 4095, 4096, 1_000_000, 1L << 39}) {
            int index = LatencyHistogram.index(value);
            assertTrue(LatencyHistogram.upperBound(index) >= value, "값 " + value);
            assertTrue(index == 0 || LatencyHistogram.upperBound(index - 1) < value, "값 " + value);
        }
    }

    private static void assertWithin(long expected, long actual) {
        assertTrue(Math.abs(actual - expected) <= expected * 0.001 + 1, "기대 " + expected + ", 실제 " + actual);
    }
}
