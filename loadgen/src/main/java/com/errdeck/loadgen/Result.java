package com.errdeck.loadgen;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * 실행 결과.
 *
 * @param statuses  응답 코드별 봉투 수
 * @param timeouts  제한 시간 안에 응답이 오지 않은 봉투 수
 * @param errors    연결 실패 같은 그 밖의 실패
 * @param errorKinds 그 밖의 실패를 원인별로 센 것
 * @param dropped   응답을 기다리는 요청이 상한에 닿아 보내지 않은 봉투 수
 * @param latency   의도한 전송 시각부터 응답까지(µs). 도구가 밀려 늦게 보낸 시간도 포함한다
 */
record Result(
        Options options,
        Duration elapsed,
        Map<Integer, Long> statuses,
        long timeouts,
        long errors,
        Map<String, Long> errorKinds,
        long dropped,
        LatencyHistogram latency) {

    Result {
        statuses = new TreeMap<>(statuses);
        errorKinds = new TreeMap<>(errorKinds);
    }

    long responses() {
        return statuses.values().stream().mapToLong(Long::longValue).sum();
    }

    long attempted() {
        return responses() + timeouts + errors + dropped;
    }

    String format() {
        StringBuilder out = new StringBuilder();
        double seconds = elapsed.toMillis() / 1000.0;
        out.append("== 결과 ==\n");
        out.append(String.format("시나리오     %s, 초당 %,d건, %s, 지문 %,d종, 봉투당 %d건%n",
                options.scenario().name().toLowerCase(java.util.Locale.ROOT), options.rate(), options.duration(),
                options.fingerprints(), options.batch()));
        out.append(String.format("걸린 시간    %.1f초%n", seconds));
        out.append(String.format("봉투         시도 %,d, 응답 %,d (초당 %,.0f)%n", attempted(), responses(), responses() / seconds));
        statuses.forEach((status, count) -> out.append(String.format("  %d         %,d%n", status, count)));
        out.append(String.format("  제한 시간  %,d%n", timeouts));
        out.append(String.format("  연결 실패  %,d%n", errors));
        errorKinds.forEach((kind, count) -> out.append(String.format("    %,d  %s%n", count, kind)));
        out.append(String.format("  보내지 않음 %,d (응답 대기 %,d건 초과)%n", dropped, options.maxInFlight()));
        out.append(String.format("응답 시간    p50 %.2fms, p99 %.2fms, 최대 %.2fms%n",
                latency.percentile(0.50) / 1000.0, latency.percentile(0.99) / 1000.0, latency.max() / 1000.0));
        return out.toString();
    }
}
