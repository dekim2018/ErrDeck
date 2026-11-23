package com.errdeck.core.event;

import com.errdeck.core.json.JsonFields;
import java.util.Map;

/**
 * 에러가 난 요청의 정보. 민감한 헤더는 SDK가 보내기 전에 지운다(15장).
 *
 * @param url         쿼리 문자열을 뺀 주소
 * @param queryString 쿼리 문자열. 없으면 null
 */
public record RequestInfo(String method, String url, String queryString, Map<String, String> headers) {

    public RequestInfo {
        headers = Copies.orderedMap(headers, "headers");
    }

    static RequestInfo fromJson(JsonFields f) {
        return new RequestInfo(
                f.optString("method"),
                f.optString("url"),
                f.optString("query_string"),
                f.stringMap("headers"));
    }

    Map<String, Object> toJson() {
        return new JsonOut()
                .put("method", method)
                .put("url", url)
                .put("query_string", queryString)
                .put("headers", headers)
                .build();
    }
}
