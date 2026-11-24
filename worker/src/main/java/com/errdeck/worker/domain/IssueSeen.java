package com.errdeck.worker.domain;

import java.time.Instant;

/** 묶음 하나에서 이슈 하나가 마지막으로 발생한 시각과 릴리스. 이슈 행은 묶음당 한 번만 고친다(6.5). */
public record IssueSeen(long issueId, Instant lastSeen, String release) {
}
