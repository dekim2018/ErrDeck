package com.errdeck.api.app;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** ClickHouse 의 건수 집계와 원본 이벤트를 읽는다. 모든 조회는 프로젝트 조건과 기간이 붙는다(15.4, 10.5). */
public interface EventStats {

    record Counts(long times, long users) {
    }

    record Bucket(Instant start, long times) {
    }

    record LatestEvent(String payload, Instant timestamp, String environment, String release, String traceId) {
    }

    /** 지문마다 기간 안의 발생 횟수와 영향 사용자 수. */
    Map<String, Counts> counts(long projectId, Collection<String> fingerprints, Instant from, Instant to);

    /** 기간 안에 많이 난 지문 순서. {@code byUsers} 면 영향 사용자 순. */
    List<String> topFingerprints(long projectId, Instant from, Instant to, boolean byUsers, int limit);

    /** 지문들의 발생 추이. 구간 길이({@code bucketSeconds})마다 하나. */
    List<Bucket> trend(long projectId, Collection<String> fingerprints, Instant from, Instant to, long bucketSeconds);

    Optional<LatestEvent> latestEvent(long projectId, Collection<String> fingerprints, Instant from, Instant to);
}
