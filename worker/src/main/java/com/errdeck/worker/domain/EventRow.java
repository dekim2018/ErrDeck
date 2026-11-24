package com.errdeck.worker.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 저장할 이벤트 한 건. ClickHouse {@code events} 테이블의 열(10.2)에 이슈를 만들 때 쓰는 값을 더했다.
 *
 * @param count   SDK가 같은 에러를 접었을 때의 실제 발생 횟수. 건수 집계에 그대로 더한다(5.6)
 * @param title   이슈 제목. 예외 타입과 메시지
 * @param culprit 맨 위 앱 프레임. 없으면 null
 */
public record EventRow(
        long projectId,
        UUID eventId,
        String fingerprint,
        int ruleVersion,
        Instant timestamp,
        Instant receivedAt,
        String environment,
        String release,
        String level,
        String excType,
        String message,
        long userHash,
        String traceId,
        Map<String, String> tags,
        boolean restored,
        String payload,
        long count,
        String title,
        String culprit) {
}
