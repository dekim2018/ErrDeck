package com.errdeck.core.fingerprint;

import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.StackFrame;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 이벤트의 지문을 계산한다(6.3).
 *
 * <p>재료는 예외 사슬의 각 예외 타입과 앱 프레임의 클래스, 메서드 이름이다. 줄 번호, 프레임워크 프레임, 프록시와 람다의
 * 번호처럼 코드가 그대로여도 바뀌는 것은 재료에서 뺀다. 그래야 같은 버그가 배포할 때마다 새 이슈로 갈라지지 않는다.
 * 반대로 맨 위 프레임 하나만 쓰지 않고 앱 프레임 전부를 써서, 같은 유틸 메서드에서 던진 다른 버그는 갈라 둔다(6.8).
 *
 * <p>스레드 안전하다. worker의 계산 풀이 함께 쓴다.
 */
public final class Fingerprinter {

    /** 규칙을 바꾸면 올린다. 같은 입력이 다른 지문이 되는 변경만 해당한다. */
    public static final int RULE_VERSION = 1;

    private static final Pattern URL_ORIGIN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*");
    private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#].*$");
    private static final Pattern LEADING_RELATIVE = Pattern.compile("^(?:\\./|/)+");

    private final AppFrames appFrames;

    public Fingerprinter(AppFrames appFrames) {
        this.appFrames = appFrames;
    }

    public Fingerprint fingerprint(Event event) {
        if (!event.fingerprint().isEmpty()) {
            return hash(Fingerprint.Basis.CUSTOM, String.join("\n", event.fingerprint()));
        }

        StringBuilder material = new StringBuilder();
        boolean anyAppFrame = false;
        for (ExceptionInfo exception : event.exceptions()) {
            material.append("type:").append(exception.type()).append('\n');
            for (String frame : appFrameKeys(exception.frames())) {
                material.append("frame:").append(frame).append('\n');
                anyAppFrame = true;
            }
        }
        if (anyAppFrame) {
            return hash(Fingerprint.Basis.STACK, material.toString());
        }

        // 앱 프레임이 하나도 없으면 프레임워크 안에서 난 에러다. 타입만 쓰면 서로 다른 원인이 뭉치므로 값을 지운 메시지를 더한다.
        StringBuilder byMessage = new StringBuilder();
        for (ExceptionInfo exception : event.exceptions()) {
            byMessage.append("type:").append(exception.type()).append('\n')
                    .append("message:").append(MessageScrubber.scrub(exception.value())).append('\n');
        }
        if (event.exceptions().isEmpty()) {
            byMessage.append("message:").append(MessageScrubber.scrub(event.message())).append('\n');
        }
        return hash(Fingerprint.Basis.MESSAGE, byMessage.toString());
    }

    /** 앱 프레임만 남기고 이름의 꼬리를 뗀 뒤, 연속으로 같은 프레임은 하나로 접는다. 무한 재귀의 깊이가 지문을 바꾸지 않게 한다. */
    List<String> appFrameKeys(List<StackFrame> frames) {
        List<String> keys = new ArrayList<>();
        for (StackFrame frame : frames) {
            if (FrameNames.isReflectionAccessor(frame.module(), frame.function()) || !appFrames.isApp(frame)) {
                continue;
            }
            String key = key(frame);
            if (keys.isEmpty() || !keys.getLast().equals(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String key(StackFrame frame) {
        String function = FrameNames.normalizeMethod(frame.function());
        if (frame.module() != null) {
            return FrameNames.normalizeClass(frame.module()) + "#" + function;
        }
        // 브라우저 프레임은 소스맵으로 복원한 원본 경로를 쓴다. 같은 파일이 개발 서버와 배포 주소로 달리 보이지 않게 출처와 쿼리를 뗀다.
        return normalizePath(frame.filename()) + "#" + function;
    }

    static String normalizePath(String filename) {
        if (filename == null) {
            return "";
        }
        String path = URL_ORIGIN.matcher(filename).replaceAll("");
        path = QUERY_OR_FRAGMENT.matcher(path).replaceAll("");
        return LEADING_RELATIVE.matcher(path).replaceAll("");
    }

    private static Fingerprint hash(Fingerprint.Basis basis, String material) {
        // 근거가 다른 지문이 우연히 같은 재료 문자열을 갖지 않게 근거를 앞에 붙인다.
        byte[] digest = sha256().digest((basis.name() + "\n" + material).getBytes(StandardCharsets.UTF_8));
        return new Fingerprint(HexFormat.of().formatHex(digest, 0, 16), RULE_VERSION, basis);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK에 SHA-256이 없다", e);
        }
    }
}
