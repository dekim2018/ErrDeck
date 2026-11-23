package com.errdeck.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 읽은 JSON 객체에서 필드를 타입을 확인하며 꺼낸다. 타입이 틀리면 필드 이름을 담아 {@link JsonException} 을 던진다.
 */
public final class JsonFields {

    private final Map<String, Object> object;
    private final String path;

    private JsonFields(Map<String, Object> object, String path) {
        this.object = object;
        this.path = path;
    }

    public static JsonFields of(Map<String, Object> object, String path) {
        return new JsonFields(object, path);
    }

    public static JsonFields of(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) map;
            return new JsonFields(object, path);
        }
        throw new JsonException(path + " 는 객체여야 한다");
    }

    public String requireString(String name) {
        String value = optString(name);
        if (value == null) {
            throw new JsonException(where(name) + " 가 없다");
        }
        return value;
    }

    public String optString(String name) {
        Object value = object.get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new JsonException(where(name) + " 는 문자열이어야 한다");
    }

    public long requireLong(String name) {
        Long value = optLong(name);
        if (value == null) {
            throw new JsonException(where(name) + " 가 없다");
        }
        return value;
    }

    public Long optLong(String name) {
        Object value = object.get(name);
        if (value == null || value instanceof Long) {
            return (Long) value;
        }
        throw new JsonException(where(name) + " 는 정수여야 한다");
    }

    public Integer optInt(String name) {
        Long value = optLong(name);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonException(where(name) + " 가 int 범위를 넘는다");
        }
        return value.intValue();
    }

    public Boolean optBoolean(String name) {
        Object value = object.get(name);
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new JsonException(where(name) + " 는 true 나 false 여야 한다");
    }

    public JsonFields optObject(String name) {
        Object value = object.get(name);
        if (value == null) {
            return null;
        }
        return of(value, where(name));
    }

    /** 없으면 빈 목록을 돌려준다. 각 원소는 {@code element} 로 바꾼다. */
    public <T> List<T> list(String name, Function<Element, T> element) {
        Object value = object.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new JsonException(where(name) + " 는 배열이어야 한다");
        }
        List<T> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(element.apply(new Element(list.get(i), where(name) + "[" + i + "]")));
        }
        return List.copyOf(result);
    }

    /** 값이 모두 문자열인 객체를 읽는다. 없으면 빈 맵을 돌려준다. 읽은 순서를 유지한다. */
    public Map<String, String> stringMap(String name) {
        Object value = object.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new JsonException(where(name) + " 는 객체여야 한다");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String s)) {
                throw new JsonException(where(name) + "." + entry.getKey() + " 는 문자열이어야 한다");
            }
            result.put((String) entry.getKey(), s);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private String where(String name) {
        return path + "." + name;
    }

    /** 배열의 원소 하나와 그 위치. */
    public record Element(Object value, String path) {

        public JsonFields asObject() {
            return JsonFields.of(value, path);
        }

        public String asString() {
            if (value instanceof String s) {
                return s;
            }
            throw new JsonException(path + " 는 문자열이어야 한다");
        }
    }
}
