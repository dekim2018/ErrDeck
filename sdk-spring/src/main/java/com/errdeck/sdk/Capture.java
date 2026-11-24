package com.errdeck.sdk;

import java.time.Instant;
import java.util.UUID;

/**
 * 요청 스레드가 큐에 넣는 것. 예외의 참조와 요청 정보 사본뿐이다. JSON 변환과 압축은 전송 스레드가 한다(11.2).
 *
 * @param count 같은 에러를 접었을 때 이 한 건이 대표하는 횟수
 */
record Capture(UUID eventId, Instant timestamp, Throwable throwable, RequestContext request, long count) {
}
