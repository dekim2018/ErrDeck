package com.errdeck.core.event;

import com.errdeck.core.json.JsonFields;
import java.util.List;
import java.util.Map;

/**
 * 스택 프레임 하나. 자바와 브라우저 프레임을 같은 모양으로 담는다.
 *
 * @param module       자바는 클래스 이름, 브라우저는 비워 둔다
 * @param function     메서드나 함수 이름
 * @param filename     자바는 소스 파일 이름, 브라우저는 번들 파일 주소. 소스맵으로 복원하면 원본 파일 경로
 * @param lineno       줄 번호. 모르면 null
 * @param colno        열 번호. 브라우저만 쓴다
 * @param inApp        앱 프레임 여부. SDK나 복원 단계가 정하지 않았으면 null 이고 worker가 정한다(6.3)
 * @param absPath      복원하기 전의 번들 주소. 복원한 프레임에만 있다
 * @param context      원본 코드의 앞뒤 줄. 복원한 프레임 중 화면에 보일 것에만 붙인다(7.3)
 * @param contextStart context 의 첫 줄 번호
 */
public record StackFrame(String module, String function, String filename, Integer lineno, Integer colno, Boolean inApp,
                         String absPath, List<String> context, Integer contextStart) {

    public StackFrame {
        context = context == null ? List.of() : List.copyOf(context);
    }

    public StackFrame(String module, String function, String filename, Integer lineno, Integer colno, Boolean inApp) {
        this(module, function, filename, lineno, colno, inApp, null, List.of(), null);
    }

    static StackFrame fromJson(JsonFields f) {
        return new StackFrame(
                f.optString("module"),
                f.optString("function"),
                f.optString("filename"),
                f.optInt("lineno"),
                f.optInt("colno"),
                f.optBoolean("in_app"),
                f.optString("abs_path"),
                f.list("context", JsonFields.Element::asString),
                f.optInt("context_start"));
    }

    Map<String, Object> toJson() {
        return new JsonOut()
                .put("module", module)
                .put("function", function)
                .put("filename", filename)
                .put("lineno", lineno)
                .put("colno", colno)
                .put("in_app", inApp)
                .put("abs_path", absPath)
                .put("context", context)
                .put("context_start", contextStart)
                .build();
    }
}
