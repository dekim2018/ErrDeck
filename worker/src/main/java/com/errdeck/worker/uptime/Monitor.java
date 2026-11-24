package com.errdeck.worker.uptime;

import java.time.Instant;

/** 점검할 모니터 하나(9.1). */
public record Monitor(long id, long projectId, String name, String url, String method, int intervalSeconds, int timeoutMs,
                      int expectedStatusMin, int expectedStatusMax, String expectedBody, Instant pausedUntil) {

    public String label() {
        return name == null || name.isBlank() ? url : name;
    }
}
