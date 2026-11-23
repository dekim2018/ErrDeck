package com.errdeck.core.event;

import com.errdeck.core.json.JsonFields;
import java.util.Map;

/**
 * 스택 프레임 하나. 자바와 브라우저 프레임을 같은 모양으로 담는다.
 *
 * @param module   자바는 클래스 이름, 브라우저는 비워 둔다
 * @param function 메서드나 함수 이름
 * @param filename 자바는 소스 파일 이름, 브라우저는 번들 파일 주소
 * @param lineno   줄 번호. 모르면 null
 * @param colno    열 번호. 브라우저만 쓴다
 * @param inApp    SDK가 판정한 앱 프레임 여부. 판정하지 않았으면 null 이고 worker가 정한다(6.3)
 */
public record StackFrame(String module, String function, String filename, Integer lineno, Integer colno, Boolean inApp) {

    static StackFrame fromJson(JsonFields f) {
        return new StackFrame(
                f.optString("module"),
                f.optString("function"),
                f.optString("filename"),
                f.optInt("lineno"),
                f.optInt("colno"),
                f.optBoolean("in_app"));
    }

    Map<String, Object> toJson() {
        return new JsonOut()
                .put("module", module)
                .put("function", function)
                .put("filename", filename)
                .put("lineno", lineno)
                .put("colno", colno)
                .put("in_app", inApp)
                .build();
    }
}
