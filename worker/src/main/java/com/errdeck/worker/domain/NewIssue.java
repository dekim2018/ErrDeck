package com.errdeck.worker.domain;

import java.time.Instant;

/** 처음 보는 지문으로 만들 이슈. 그 지문의 첫 이벤트로 채운다. */
public record NewIssue(IssueKey key, int ruleVersion, String title, String culprit, Instant firstSeen, Instant lastSeen, String release) {
}
