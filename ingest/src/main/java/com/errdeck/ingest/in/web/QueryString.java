package com.errdeck.ingest.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 쿼리 문자열에서 값 하나를 꺼낸다.
 *
 * <p>{@code request.getParameter} 는 본문 형식이 폼이면 본문까지 읽는다. 본문은 봉투이므로 쿼리만 직접 읽는다.
 */
final class QueryString {

    private QueryString() {
    }

    static String get(HttpServletRequest request, String name) {
        String query = request.getQueryString();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
