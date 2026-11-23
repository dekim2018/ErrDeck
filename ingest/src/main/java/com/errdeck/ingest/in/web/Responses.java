package com.errdeck.ingest.in.web;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;

final class Responses {

    private Responses() {
    }

    /** Retry-After 는 초 단위 정수다. 1초보다 짧으면 1초로 올린다. */
    static String retryAfterSeconds(Duration wait) {
        long millis = Math.max(1, wait.toMillis());
        return Long.toString((millis + 999) / 1000);
    }

    static void reject(HttpServletResponse response, int status, Duration retryAfter) {
        response.setStatus(status);
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfterSeconds(retryAfter));
        }
    }
}
