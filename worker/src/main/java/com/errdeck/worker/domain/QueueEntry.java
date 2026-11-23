package com.errdeck.worker.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 대기열 항목 하나. ingest 가 넣은 압축된 봉투와 받은 시각이다.
 *
 * @param id         스트림 항목 ID. 확인 처리에 쓴다
 * @param receivedAt ingest 가 받은 시각. 수신부터 저장까지의 시간(errdeck_e2e_latency_seconds)을 잰다
 */
public record QueueEntry(QueueStream stream, String id, long projectId, Instant receivedAt, byte[] body) {

    public QueueEntry {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(id, "id");
    }

    public EntryRef ref() {
        return new EntryRef(stream, id);
    }
}
