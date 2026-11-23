package com.errdeck.core.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class Copies {

    private Copies() {
    }

    /** 순서를 지키는 불변 사본. {@link Map#copyOf} 는 순서를 잃어 다시 쓸 때 바이트가 달라진다. */
    static Map<String, String> orderedMap(Map<String, String> map, String name) {
        if (map == null) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(Objects.requireNonNull(k, name + " 의 키"), Objects.requireNonNull(v, name + " 의 값")));
        return Collections.unmodifiableMap(copy);
    }
}
