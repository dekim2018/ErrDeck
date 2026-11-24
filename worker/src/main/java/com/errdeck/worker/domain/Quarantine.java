package com.errdeck.worker.domain;

/**
 * 처리할 수 없어 격리 테이블로 옮길 원문(6.7). 해석할 수 없는 이벤트 하나가 대기열 전체를 막지 않게 한다.
 *
 * @param raw 대기열 항목이면 압축된 봉투, 이벤트 하나면 그 JSON
 */
public record Quarantine(long projectId, byte[] raw, String reason) {
}
