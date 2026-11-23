package com.errdeck.core.event;

import com.errdeck.core.json.JsonFields;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 예외 사슬의 고리 하나.
 *
 * @param type   예외 타입. 자바는 전체 클래스 이름, 브라우저는 {@code TypeError} 같은 이름
 * @param value  예외 메시지
 * @param frames 예외가 난 곳(가장 안쪽 호출)이 먼저 온다. 자바의 {@code getStackTrace()} 와 같은 순서다
 */
public record ExceptionInfo(String type, String value, List<StackFrame> frames) {

    public ExceptionInfo {
        Objects.requireNonNull(type, "type");
        frames = frames == null ? List.of() : List.copyOf(frames);
    }

    static ExceptionInfo fromJson(JsonFields f) {
        return new ExceptionInfo(
                f.requireString("type"),
                f.optString("value"),
                f.list("frames", e -> StackFrame.fromJson(e.asObject())));
    }

    Map<String, Object> toJson() {
        return new JsonOut()
                .put("type", type)
                .put("value", value)
                .put("frames", frames.stream().map(StackFrame::toJson).toList())
                .build();
    }
}
