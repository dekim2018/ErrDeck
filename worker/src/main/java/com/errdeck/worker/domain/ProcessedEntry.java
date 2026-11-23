package com.errdeck.worker.domain;

import java.time.Instant;
import java.util.List;

/**
 * 계산을 마친 대기열 항목. 쓸 행이 없어도(트랜잭션, 해석할 수 없는 봉투) 쓰기 스레드를 거쳐야 한다.
 * 앞선 묶음이 쓰인 뒤에 확인 처리해야 순서가 꼬이지 않기 때문이다.
 */
public record ProcessedEntry(EntryRef ref, Instant receivedAt, List<EventRow> rows) {

    public ProcessedEntry {
        rows = List.copyOf(rows);
    }
}
