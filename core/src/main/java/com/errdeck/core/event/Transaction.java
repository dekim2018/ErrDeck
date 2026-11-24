package com.errdeck.core.event;

import com.errdeck.core.json.Json;
import com.errdeck.core.json.JsonException;
import com.errdeck.core.json.JsonFields;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 추적한 요청 하나(8.2). 봉투의 transaction 항목 본문이다. 이 서비스에서 일어난 구간만 담고, 서비스 사이는 trace_id 로 잇는다.
 *
 * @param traceId      요청 전체의 ID. 16진수 32자
 * @param spanId       이 트랜잭션의 ID. 16진수 16자
 * @param parentSpanId 이 트랜잭션을 부른 앞 서비스의 구간. 처음 시작한 곳이면 null
 * @param name         경로 패턴(`GET /api/matches/{id}`) 이나 페이지 이름. 실제 주소를 쓰지 않는다(8.3)
 * @param op           {@code http.server}, {@code pageload}, {@code navigation} 같은 종류
 * @param start        시작 시각
 * @param durationMs   걸린 시간
 * @param status       {@code ok}, {@code error}, 또는 HTTP 상태 코드
 * @param partial      시작할 때 표본으로 뽑히지 않았지만 에러나 느림으로 끝에서 남긴 것(8.4). 앞뒤 서비스의 구간이 없다
 * @param spans        안쪽 구간. 최대 {@link #MAX_SPANS} 개
 */
public record Transaction(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String op,
        Instant start,
        long durationMs,
        String status,
        String environment,
        String release,
        boolean partial,
        List<Span> spans) {

    /** 트랜잭션당 스팬 상한(8.5). SDK 가 지키고 worker 는 넘는 것을 자른다. */
    public static final int MAX_SPANS = 500;

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    public Transaction {
        Objects.requireNonNull(start, "start");
        requireMatch(TRACE_ID, traceId, "trace_id");
        requireMatch(SPAN_ID, spanId, "span_id");
        if (parentSpanId != null) {
            requireMatch(SPAN_ID, parentSpanId, "parent_span_id");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 이 없다");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("duration_ms 는 0 이상이어야 한다: " + durationMs);
        }
        op = op == null ? "http.server" : op;
        status = status == null ? "ok" : status;
        spans = spans == null ? List.of() : List.copyOf(spans.size() > MAX_SPANS ? spans.subList(0, MAX_SPANS) : spans);
    }

    /**
     * 안쪽 구간 하나.
     *
     * @param startOffsetMs 트랜잭션 시작부터의 시간
     * @param repeat        같은 내용이 연속으로 반복되어 접힌 횟수(8.5). 접지 않았으면 1. 시간은 합계다
     */
    public record Span(String spanId, String parentSpanId, String op, String description, long startOffsetMs, long durationMs,
                       int repeat) {

        public Span {
            requireMatch(SPAN_ID, spanId, "spans.span_id");
            if (parentSpanId != null) {
                requireMatch(SPAN_ID, parentSpanId, "spans.parent_span_id");
            }
            op = op == null ? "custom" : op;
            description = description == null ? "" : description;
            if (startOffsetMs < 0 || durationMs < 0 || repeat < 1) {
                throw new IllegalArgumentException("스팬의 시간은 0 이상, repeat 는 1 이상이어야 한다");
            }
        }

        static Span fromJson(JsonFields f) {
            Integer repeat = f.optInt("repeat");
            return new Span(f.requireString("span_id"), f.optString("parent_span_id"), f.optString("op"), f.optString("description"),
                    f.requireLong("start_offset_ms"), f.requireLong("duration_ms"), repeat == null ? 1 : repeat);
        }

        Object toJson() {
            return new JsonOut()
                    .put("span_id", spanId)
                    .put("parent_span_id", parentSpanId)
                    .put("op", op)
                    .put("description", description)
                    .put("start_offset_ms", startOffsetMs)
                    .put("duration_ms", durationMs)
                    .put("repeat", repeat == 1 ? null : repeat)
                    .build();
        }
    }

    public static Transaction fromJson(byte[] utf8) {
        try {
            JsonFields f = JsonFields.of(Json.parseObject(utf8), "transaction");
            Boolean partial = f.optBoolean("partial");
            return new Transaction(
                    f.requireString("trace_id"),
                    f.requireString("span_id"),
                    f.optString("parent_span_id"),
                    f.requireString("name"),
                    f.optString("op"),
                    Event.parseInstant(f.requireString("start_timestamp"), "transaction.start_timestamp"),
                    f.requireLong("duration_ms"),
                    f.optString("status"),
                    f.optString("environment"),
                    f.optString("release"),
                    partial != null && partial,
                    f.list("spans", e -> Span.fromJson(e.asObject())));
        } catch (JsonException e) {
            throw new IllegalArgumentException("트랜잭션 형식이 틀렸다: " + e.getMessage(), e);
        }
    }

    public byte[] toJson() {
        return Json.writeBytes(new JsonOut()
                .put("trace_id", traceId)
                .put("span_id", spanId)
                .put("parent_span_id", parentSpanId)
                .put("name", name)
                .put("op", op)
                .put("start_timestamp", start.toString())
                .put("duration_ms", durationMs)
                .put("status", status)
                .put("environment", environment)
                .put("release", release)
                .put("partial", partial ? Boolean.TRUE : null)
                .put("spans", spans.stream().map(Span::toJson).toList())
                .build());
    }

    private static void requireMatch(Pattern pattern, String value, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " 형식이 틀렸다: " + value);
        }
    }
}
