package com.errdeck.sdk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 보내기 전에 민감한 값을 지운다(15.6). 민감한 값이 네트워크를 건너지 않는 것이 가장 안전하다.
 * worker 가 저장 전에 한 번 더 지운다.
 */
final class Sanitizer {

    static final String FILTERED = "[Filtered]";

    private static final Set<String> HEADERS = Set.of("authorization", "cookie", "set-cookie", "proxy-authorization");
    private static final String[] SENSITIVE_PARTS = {"password", "token", "secret", "card", "key"};

    private Sanitizer() {
    }

    static Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            out.put(name, HEADERS.contains(lower) || sensitive(lower) ? FILTERED : value);
        });
        return out;
    }

    /** {@code a=1&token=xyz} 는 {@code a=1&token=[Filtered]} 가 된다. */
    static String query(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&", -1)) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            out.append(eq >= 0 && sensitive(name.toLowerCase(Locale.ROOT)) ? name + "=" + FILTERED : pair);
        }
        return out.toString();
    }

    static boolean sensitive(String lowerName) {
        for (String part : SENSITIVE_PARTS) {
            if (lowerName.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
