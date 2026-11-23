package com.errdeck.worker.domain;

/** 확인 처리할 대기열 항목의 위치. */
public record EntryRef(QueueStream stream, String id) {
}
