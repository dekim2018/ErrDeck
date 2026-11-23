package com.errdeck.core.fingerprint;

import com.errdeck.core.event.StackFrame;
import java.util.List;

/**
 * 내 코드에 속한 프레임인지 판정한다(6.3). 프레임워크 내부 프레임은 버전만 올려도 바뀌므로 지문에서 뺀다.
 *
 * <p>SDK가 {@code in_app} 을 정했으면 그것을 따른다. 정하지 않았으면 자바 프레임은 패키지 목록으로,
 * 브라우저 프레임은 {@code node_modules} 밖의 파일인지로 판정한다.
 *
 * @param inAppPackages 앱 패키지. 예: {@code com.livebroadcast}. 하위 패키지도 앱으로 본다
 */
public record AppFrames(List<String> inAppPackages) {

    public AppFrames {
        inAppPackages = List.copyOf(inAppPackages);
    }

    public boolean isApp(StackFrame frame) {
        if (frame.inApp() != null) {
            return frame.inApp();
        }
        if (frame.module() != null) {
            return inPackages(frame.module());
        }
        return frame.filename() != null && !frame.filename().contains("node_modules/");
    }

    private boolean inPackages(String className) {
        for (String pkg : inAppPackages) {
            if (className.startsWith(pkg) && (className.length() == pkg.length() || className.charAt(pkg.length()) == '.')) {
                return true;
            }
        }
        return false;
    }
}
