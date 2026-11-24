package com.errdeck.worker.domain;

import java.time.Instant;

/**
 * 묶음 하나에서 이슈 하나가 마지막으로 발생한 시각과 릴리스. 이슈 행은 묶음당 한 번만 고친다(6.5).
 *
 * @param events 이 묶음에서 이 이슈에 들어온 발생 횟수(접힌 횟수 포함). 무시의 횟수 조건을 센다(6.6)
 */
public record IssueSeen(long issueId, Instant lastSeen, String release, long events) {

    public IssueSeen(long issueId, Instant lastSeen, String release) {
        this(issueId, lastSeen, release, 1);
    }
}
