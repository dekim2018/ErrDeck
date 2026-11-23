package com.errdeck.core.fingerprint;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 이벤트의 지문.
 *
 * @param value       SHA-256 의 앞 16바이트를 소문자 16진수 32자로. ClickHouse 의 {@code FixedString(32)} 에 들어간다(10.2)
 * @param ruleVersion 지문을 만든 규칙의 버전. 규칙을 바꾸면 올리고, 전환 기간에는 옛 버전으로도 계산한다(6.4)
 * @param basis       지문을 무엇으로 만들었는지. 화면에서 왜 묶였는지 설명할 때 쓴다
 */
public record Fingerprint(String value, int ruleVersion, Basis basis) {

    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");

    public enum Basis {
        /** SDK를 쓰는 쪽이 직접 지정한 값. */
        CUSTOM,
        /** 예외 타입과 앱 프레임. */
        STACK,
        /** 앱 프레임이 하나도 없어 예외 타입과 값을 지운 메시지로 대신한 것. */
        MESSAGE
    }

    public Fingerprint {
        Objects.requireNonNull(basis, "basis");
        if (value == null || !HEX32.matcher(value).matches()) {
            throw new IllegalArgumentException("지문은 소문자 16진수 32자여야 한다: " + value);
        }
    }
}
