package com.errdeck.sdk.transport;

/** client_report 에 담는 버린 이유(5.6). */
public enum DiscardReason {
    /** 전송 큐가 차서 넣지 못했다. */
    QUEUE_OVERFLOW("queue_overflow"),
    /** ingest 가 429 로 프로젝트 한도를 알려 쉬는 동안 생겼다. */
    RATE_LIMITED("rate_limited"),
    /** ingest 가 503 으로 대기열이 찼다고 알려 쉬는 동안 생겼다. */
    QUEUE_FULL("queue_full"),
    /** 봉투가 너무 커 413 을 받았다. */
    TOO_LARGE("too_large"),
    /** 다시 보내도 연결하지 못했다. */
    NETWORK_ERROR("network_error"),
    /** 키나 출처가 틀려 401, 403 을 받아 전송을 멈췄다. */
    UNAUTHORIZED("unauthorized");

    private final String wireName;

    DiscardReason(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
