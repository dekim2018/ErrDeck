package com.errdeck.worker.domain;

import com.errdeck.core.event.Transaction;
import java.time.Instant;

/** transactions 테이블의 한 행이 될 트랜잭션. 프로젝트와 받은 시각은 대기열 항목에서 온다. */
public record TransactionRow(long projectId, Instant receivedAt, Transaction transaction) {
}
