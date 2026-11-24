package com.errdeck.sdk;

import java.util.Map;

/**
 * 예외가 난 요청의 정보 사본. 요청 객체는 응답이 끝나면 재사용되므로 요청 스레드에서 값만 복사해 둔다.
 * 민감한 값은 보내기 전에 전송 스레드가 지운다(15.6).
 */
public record RequestContext(String method, String url, String queryString, Map<String, String> headers, String userId) {

    public RequestContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
