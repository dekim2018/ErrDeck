package com.errdeck.sdk;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 주소의 경로와 예외 메시지에 실린 비밀(공유 링크 토큰처럼 경로 조각인 것, ticket=..., Bearer ...)을 보내기 전에 가린다(15.6). 쿼리의 민감한 이름은
 * {@link Sanitizer} 가 가린다. 패턴은 {@code errdeck.redact-paths} 로 준다.
 *
 * <p>패턴에 괄호가 있으면 첫 괄호까지 남기고 나머지를 가린다. {@code (/s/)[^/?#]+} 는 {@code /s/abc} 를 {@code /s/[Filtered]} 로 바꾼다.
 */
public final class Redaction {

    private static volatile List<Pattern> patterns = List.of();

    private Redaction() {
    }

    public static void configure(List<String> regexes) {
        patterns = regexes == null ? List.of() : regexes.stream().filter(r -> r != null && !r.isBlank()).map(Pattern::compile).toList();
    }

    public static String apply(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Pattern p : patterns) {
            out = p.matcher(out).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                    (m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "") + Sanitizer.FILTERED));
        }
        return out;
    }
}
