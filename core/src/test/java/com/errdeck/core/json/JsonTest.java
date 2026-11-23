package com.errdeck.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void 객체와_배열과_기본값을_읽는다() {
        Object value = Json.parse("{\"a\":1,\"b\":[true,false,null],\"c\":\"x\",\"d\":-2.5e1,\"e\":{}}");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1L);
        expected.put("b", java.util.Arrays.asList(true, false, null));
        expected.put("c", "x");
        expected.put("d", -25.0);
        expected.put("e", Map.of());
        assertEquals(expected, value);
    }

    @Test
    void 쓰고_다시_읽으면_같은_값이고_키_순서를_지킨다() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("z", "마지막에 넣은 키가 아니라 처음 넣은 키");
        value.put("a", List.of(1L, 2L));
        value.put("m", 0.5);

        String text = Json.write(value);

        assertEquals("{\"z\":\"마지막에 넣은 키가 아니라 처음 넣은 키\",\"a\":[1,2],\"m\":0.5}", text);
        assertEquals(value, Json.parse(text));
    }

    @Test
    void 줄바꿈과_제어_문자는_이스케이프해서_한_줄로_쓴다() {
        String text = Json.write("a\nb\"c\\d\te\u0001");

        assertEquals("\"a\\nb\\\"c\\\\d\\te\\u0001\"", text);
        assertEquals("a\nb\"c\\d\te\u0001", Json.parse(text));
    }

    @Test
    void 유니코드_이스케이프를_읽는다() {
        assertEquals("한글 😀", Json.parse("\"\\ud55c\\uae00 \\ud83d\\ude00\""));
    }

    @Test
    void 값_뒤에_남은_문자가_있으면_거부한다() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1} x"));
    }

    @Test
    void 닫히지_않은_입력을_거부한다() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("\"abc"));
    }

    @Test
    void 형식이_틀린_값을_거부한다() {
        assertThrows(JsonException.class, () -> Json.parse("{a:1}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("1."));
        assertThrows(JsonException.class, () -> Json.parse("tru"));
        assertThrows(JsonException.class, () -> Json.parse("\"\\x\""));
        assertThrows(JsonException.class, () -> Json.parse("\"줄\n바꿈\""));
    }

    @Test
    void 중복된_키를_거부한다() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void long_범위를_넘는_정수를_거부한다() {
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertThrows(JsonException.class, () -> Json.parse("9223372036854775808"));
    }

    @Test
    void 중첩이_상한을_넘으면_스택이_넘치기_전에_거부한다() {
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        String deep = "[".repeat(100_000);

        assertEquals(List.class.isInstance(Json.parse(ok)), true);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void null_은_null_로_읽는다() {
        assertNull(Json.parse("null"));
    }
}
