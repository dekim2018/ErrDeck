package com.errdeck.core.event;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 모델을 JSON 객체로 옮길 때 쓴다. 비어 있는 값은 빼서 봉투를 작게 하고, 넣는 순서를 지켜 같은 모델이 같은 바이트가 되게 한다. */
final class JsonOut {

    private final Map<String, Object> object = new LinkedHashMap<>();

    JsonOut put(String name, Object value) {
        if (value == null) {
            return this;
        }
        if (value instanceof Collection<?> c && c.isEmpty()) {
            return this;
        }
        if (value instanceof Map<?, ?> m && m.isEmpty()) {
            return this;
        }
        object.put(name, value);
        return this;
    }

    Map<String, Object> build() {
        return object;
    }
}
