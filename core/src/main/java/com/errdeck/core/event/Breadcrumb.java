package com.errdeck.core.event;

import com.errdeck.core.json.JsonFields;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 에러 직전의 동작 기록 하나. 라우트 이동, 클릭, API 호출 같은 것이다(11.3).
 *
 * @param category 동작의 종류. 예: {@code navigation}, {@code ui.click}, {@code http}
 */
public record Breadcrumb(Instant timestamp, String category, String message, Map<String, String> data) {

    public Breadcrumb {
        Objects.requireNonNull(timestamp, "timestamp");
        data = Copies.orderedMap(data, "data");
    }

    static Breadcrumb fromJson(JsonFields f) {
        return new Breadcrumb(
                Event.parseInstant(f.requireString("timestamp"), "breadcrumbs.timestamp"),
                f.optString("category"),
                f.optString("message"),
                f.stringMap("data"));
    }

    Map<String, Object> toJson() {
        return new JsonOut()
                .put("timestamp", timestamp.toString())
                .put("category", category)
                .put("message", message)
                .put("data", data)
                .build();
    }
}
