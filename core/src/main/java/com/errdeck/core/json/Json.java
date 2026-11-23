package com.errdeck.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 봉투의 JSON 줄을 읽고 쓴다.
 *
 * <p>core는 JDK만 쓰기로 했으므로(WORKFLOW.md 2.4) JSON 라이브러리 대신 필요한 만큼만 직접 구현한다.
 * 읽은 값은 {@code Map<String, Object>}, {@code List<Object>}, {@code String}, {@code Long},
 * {@code Double}, {@code Boolean}, {@code null} 중 하나다. 객체는 읽은 순서를 유지한다.
 */
public final class Json {

    /** 깊게 중첩된 입력으로 스택이 넘치지 않게 막는다. 봉투의 정상 이벤트는 깊이 10을 넘지 않는다. */
    static final int MAX_DEPTH = 64;

    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("값 뒤에 남은 문자가 있다");
        }
        return value;
    }

    public static Object parse(byte[] utf8) {
        return parse(new String(utf8, StandardCharsets.UTF_8));
    }

    public static Map<String, Object> parseObject(byte[] utf8) {
        Object value = parse(utf8);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) map;
            return object;
        }
        throw new JsonException("JSON 객체가 아니다");
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    public static byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(out, s);
            case Boolean b -> out.append(b);
            case Long l -> out.append(l);
            case Integer i -> out.append(i);
            case Double d -> writeDouble(out, d);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, (String) entry.getKey());
                    out.append(':');
                    writeValue(out, entry.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeValue(out, list.get(i));
                }
                out.append(']');
            }
            default -> throw new JsonException("JSON으로 쓸 수 없는 값: " + value.getClass().getName());
        }
    }

    private static void writeDouble(StringBuilder out, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new JsonException("JSON은 NaN과 무한대를 담지 못한다");
        }
        out.append(d);
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // 줄 단위 형식이므로 제어 문자는 반드시 이스케이프한다. 그 밖의 문자는 UTF-8 그대로 둔다.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        JsonException error(String message) {
            return new JsonException(message + " (위치 " + pos + ")");
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) {
            if (atEnd()) {
                throw error("값이 없다");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("알 수 없는 문자 '" + c + "'");
                }
            };
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("알 수 없는 값");
            }
            pos += literal.length();
            return value;
        }

        private Map<String, Object> readObject(int depth) {
            checkDepth(depth);
            pos++;
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("객체의 키는 문자열이어야 한다");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (object.containsKey(key)) {
                    throw error("키가 중복된다: " + key);
                }
                object.put(key, readValue(depth));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw error("객체에 ',' 나 '}' 가 와야 한다");
                }
            }
        }

        private List<Object> readArray(int depth) {
            checkDepth(depth);
            pos++;
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return array;
            }
            while (true) {
                skipWhitespace();
                array.add(readValue(depth));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return array;
                }
                if (c != ',') {
                    throw error("배열에 ',' 나 ']' 가 와야 한다");
                }
            }
        }

        private void checkDepth(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("중첩이 " + MAX_DEPTH + "단계를 넘는다");
            }
        }

        private String readString() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("문자열이 닫히지 않았다");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("문자열 안에 제어 문자가 있다");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("\\u 뒤에 16진수 4자리가 와야 한다");
                        }
                        try {
                            out.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                        } catch (NumberFormatException ex) {
                            throw error("\\u 뒤에 16진수 4자리가 와야 한다");
                        }
                        pos += 4;
                    }
                    default -> throw error("알 수 없는 이스케이프 \\" + e);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (atEnd() || !isDigit(peek())) {
                throw error("숫자가 아니다");
            }
            if (peek() == '0') {
                pos++;
            } else {
                skipDigits();
            }
            boolean integral = true;
            if (!atEnd() && peek() == '.') {
                integral = false;
                pos++;
                if (atEnd() || !isDigit(peek())) {
                    throw error("소수점 뒤에 숫자가 와야 한다");
                }
                skipDigits();
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                integral = false;
                pos++;
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    pos++;
                }
                if (atEnd() || !isDigit(peek())) {
                    throw error("지수에 숫자가 와야 한다");
                }
                skipDigits();
            }
            String number = text.substring(start, pos);
            if (integral) {
                BigInteger value = new BigInteger(number);
                if (value.bitLength() >= 64) {
                    throw error("정수가 long 범위를 넘는다");
                }
                return value.longValue();
            }
            return new BigDecimal(number).doubleValue();
        }

        private void skipDigits() {
            while (!atEnd() && isDigit(peek())) {
                pos++;
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private char peek() {
            if (atEnd()) {
                throw error("입력이 중간에 끝났다");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw error("'" + expected + "' 가 와야 한다");
            }
        }
    }
}
