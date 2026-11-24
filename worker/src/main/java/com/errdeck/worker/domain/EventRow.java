package com.errdeck.worker.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** ClickHouse {@code events} 테이블의 한 행(10.2). */
public record EventRow(
        long projectId,
        UUID eventId,
        String fingerprint,
        Instant timestamp,
        Instant receivedAt,
        String environment,
        String release,
        String level,
        String excType,
        String message,
        long userHash,
        String traceId,
        Map<String, String> tags,
        boolean restored,
        String payload) {
}
