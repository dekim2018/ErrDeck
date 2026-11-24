package com.errdeck.worker.domain;

import java.time.Instant;

/** ClickHouse {@code event_ticks} 의 한 행. 원본을 버려도 건수는 여기로 남는다(10.3). */
public record Tick(long projectId, String fingerprint, Instant timestamp, long count, long userHash) {
}
