package com.errdeck.core.event;

import com.errdeck.core.json.Json;
import com.errdeck.core.json.JsonException;
import com.errdeck.core.json.JsonFields;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 에러 이벤트 1건. 봉투의 event 항목 본문이다(5.3).
 *
 * @param eventId     SDK가 붙인 ID. worker가 중복을 거를 때 쓴다(6.7)
 * @param timestamp   에러가 난 시각
 * @param level       {@code fatal}, {@code error}, {@code warning}, {@code info} 중 하나
 * @param message     예외 없이 보낸 메시지. 예외가 있으면 비워도 된다
 * @param userId      사용자 식별자. 저장할 때는 해시로 바꾸고 원래 값은 원본 안에만 둔다(10.2)
 * @param traceId     연결된 추적의 ID. 16진수 32자
 * @param fingerprint SDK가 직접 지정한 지문 재료. 비어 있으면 worker가 규칙으로 계산한다(6.3)
 * @param count       SDK가 같은 에러를 접었을 때의 실제 발생 횟수. 접지 않았으면 1이다(11.1)
 * @param exceptions  예외 사슬. 바깥 예외가 먼저, 원인이 뒤에 온다
 */
public record Event(
        UUID eventId,
        Instant timestamp,
        String level,
        String message,
        String environment,
        String release,
        String userId,
        String traceId,
        List<String> fingerprint,
        long count,
        List<ExceptionInfo> exceptions,
        RequestInfo request,
        Map<String, String> tags,
        List<Breadcrumb> breadcrumbs) {

    public static final String DEFAULT_LEVEL = "error";

    private static final List<String> LEVELS = List.of("fatal", "error", "warning", "info");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    public Event {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(timestamp, "timestamp");
        level = level == null ? DEFAULT_LEVEL : level;
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("level 은 " + LEVELS + " 중 하나여야 한다: " + level);
        }
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("trace_id 는 소문자 16진수 32자여야 한다: " + traceId);
        }
        if (count < 1) {
            throw new IllegalArgumentException("count 는 1 이상이어야 한다: " + count);
        }
        fingerprint = fingerprint == null ? List.of() : List.copyOf(fingerprint);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        tags = Copies.orderedMap(tags, "tags");
        breadcrumbs = breadcrumbs == null ? List.of() : List.copyOf(breadcrumbs);
    }

    /** 사슬의 맨 바깥 예외. 이슈 목록에 보이는 예외 타입이다. 예외가 없으면 null. */
    public ExceptionInfo primaryException() {
        return exceptions.isEmpty() ? null : exceptions.getFirst();
    }

    public static Event fromJson(byte[] utf8) {
        Map<String, Object> object;
        try {
            object = Json.parseObject(utf8);
        } catch (JsonException e) {
            throw new IllegalArgumentException("이벤트가 JSON 객체가 아니다: " + e.getMessage(), e);
        }
        try {
            return fromJson(JsonFields.of(object, "event"));
        } catch (JsonException e) {
            throw new IllegalArgumentException("이벤트 형식이 틀렸다: " + e.getMessage(), e);
        }
    }

    private static Event fromJson(JsonFields f) {
        Long count = f.optLong("count");
        JsonFields request = f.optObject("request");
        return new Event(
                parseUuid(f.requireString("event_id")),
                parseInstant(f.requireString("timestamp"), "event.timestamp"),
                f.optString("level"),
                f.optString("message"),
                f.optString("environment"),
                f.optString("release"),
                f.optString("user_id"),
                f.optString("trace_id"),
                f.list("fingerprint", JsonFields.Element::asString),
                count == null ? 1 : count,
                f.list("exceptions", e -> ExceptionInfo.fromJson(e.asObject())),
                request == null ? null : RequestInfo.fromJson(request),
                f.stringMap("tags"),
                f.list("breadcrumbs", e -> Breadcrumb.fromJson(e.asObject())));
    }

    public byte[] toJson() {
        return Json.writeBytes(new JsonOut()
                .put("event_id", eventId.toString())
                .put("timestamp", timestamp.toString())
                .put("level", level)
                .put("message", message)
                .put("environment", environment)
                .put("release", release)
                .put("user_id", userId)
                .put("trace_id", traceId)
                .put("fingerprint", fingerprint)
                // 대부분의 이벤트는 접히지 않으므로 기본값 1은 쓰지 않는다.
                .put("count", count == 1 ? null : count)
                .put("exceptions", exceptions.stream().map(ExceptionInfo::toJson).toList())
                .put("request", request == null ? null : request.toJson())
                .put("tags", tags)
                .put("breadcrumbs", breadcrumbs.stream().map(Breadcrumb::toJson).toList())
                .build());
    }

    /** {@link UUID#fromString} 은 자릿수가 모자란 값도 받으므로 형식을 먼저 확인한다. */
    private static UUID parseUuid(String text) {
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new JsonException("event.event_id 는 소문자 UUID 형식이어야 한다: " + text);
        }
        return UUID.fromString(text);
    }

    static Instant parseInstant(String text, String path) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new JsonException(path + " 는 ISO-8601 UTC 시각이어야 한다: " + text);
        }
    }
}
