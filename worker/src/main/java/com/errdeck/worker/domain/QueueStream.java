package com.errdeck.worker.domain;

/** ingest 가 쌓는 대기열 두 개(5.5). */
public enum QueueStream {

    EVENTS("errdeck:stream:events"),
    TX("errdeck:stream:tx");

    private final String key;

    QueueStream(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
