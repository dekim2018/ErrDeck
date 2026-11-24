package com.errdeck.worker.domain;

/** 프로젝트 안의 지문 하나. 이슈를 찾는 열쇠다(6.4). */
public record IssueKey(long projectId, String fingerprint) {
}
