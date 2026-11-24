package com.errdeck.worker.domain;

import java.time.Instant;
import java.util.List;

/**
 * 계산을 마친 대기열 항목. 쓸 행이 없어도(트랜잭션, 격리할 것만 있는 항목) 쓰기 스레드를 거쳐야 한다.
 * 앞선 묶음이 쓰인 뒤에 확인 처리해야 순서가 꼬이지 않기 때문이다.
 *
 * @param quarantined 격리 테이블로 옮길 원문. 봉투 전체이거나 그 안의 이벤트 하나다
 */
public record ProcessedEntry(EntryRef ref, Instant receivedAt, List<EventRow> rows, List<Quarantine> quarantined) {

    public ProcessedEntry {
        rows = List.copyOf(rows);
        quarantined = List.copyOf(quarantined);
    }

    public ProcessedEntry(EntryRef ref, Instant receivedAt, List<EventRow> rows) {
        this(ref, receivedAt, rows, List.of());
    }
}
