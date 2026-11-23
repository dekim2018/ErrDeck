package com.errdeck.ingest.domain;

/**
 * 대기열 두 개. 트랜잭션이 많아도 에러가 그 뒤에 줄 서지 않도록 나눈다(5.5).
 * SDK 는 트랜잭션 봉투를 따로 보내며 {@code ?kind=tx} 로 표시한다. ingest 는 본문을 풀지 않으므로 표시를 믿는다.
 */
public enum Stream {

    EVENTS("errdeck:stream:events", "events"),
    TX("errdeck:stream:tx", "tx");

    private final String key;
    private final String tag;

    Stream(String key, String tag) {
        this.key = key;
        this.tag = tag;
    }

    public String key() {
        return key;
    }

    /** 지표의 stream 태그 값. */
    public String tag() {
        return tag;
    }

    /** 쿼리의 kind 값. 없으면 에러 대기열이다. 모르는 값이면 null. */
    public static Stream fromKind(String kind) {
        if (kind == null || kind.isEmpty() || kind.equals("event")) {
            return EVENTS;
        }
        if (kind.equals("tx")) {
            return TX;
        }
        return null;
    }
}
