package com.errdeck.worker.uptime;

import java.time.Instant;

/** 점검 한 번의 결과. uptime_checks 의 한 행이다. */
public record CheckResult(long monitorId, long projectId, Instant timestamp, boolean ok, int statusCode, long durationMs, String error,
                          Instant certExpiresAt) {
}
