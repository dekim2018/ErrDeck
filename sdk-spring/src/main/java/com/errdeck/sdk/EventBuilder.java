package com.errdeck.sdk;

import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.RequestInfo;
import com.errdeck.core.event.StackFrame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 잡은 예외를 봉투에 담을 이벤트로 바꾼다. 전송 스레드에서만 부른다. 요청 스레드에 이 비용을 두지 않는다(11.2).
 */
final class EventBuilder {

    /** 6.1 의 정규화와 같은 상한. worker 도 같은 값으로 자르지만 네트워크로 보내는 양부터 줄인다. */
    static final int MAX_FRAMES = 250;
    static final int MAX_CHAIN = 10;
    static final int MAX_MESSAGE = 8 * 1024;

    private final ErrDeckOptions options;

    EventBuilder(ErrDeckOptions options) {
        this.options = options;
    }

    Event build(Capture capture) {
        RequestContext request = capture.request();
        return new Event(
                capture.eventId(),
                capture.timestamp(),
                Event.DEFAULT_LEVEL,
                null,
                options.environment(),
                options.release(),
                request == null ? null : request.userId(),
                null,
                List.of(),
                Math.max(1, capture.count()),
                chain(capture.throwable()),
                request == null ? null : new RequestInfo(request.method(), request.url(), Sanitizer.query(request.queryString()),
                        Sanitizer.headers(request.headers())),
                Map.of(),
                List.of());
    }

    /** 바깥 예외부터 원인 순서로. 원인이 자기 자신을 가리키는 고리가 있어도 멈춘다. */
    private List<ExceptionInfo> chain(Throwable throwable) {
        List<ExceptionInfo> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = throwable; t != null && chain.size() < MAX_CHAIN && seen.add(t); t = t.getCause()) {
            chain.add(new ExceptionInfo(t.getClass().getName(), truncate(t.getMessage()), frames(t.getStackTrace())));
        }
        return chain;
    }

    private List<StackFrame> frames(StackTraceElement[] trace) {
        int count = Math.min(trace.length, MAX_FRAMES);
        List<StackFrame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StackTraceElement element = trace[i];
            frames.add(new StackFrame(
                    element.getClassName(),
                    element.getMethodName(),
                    element.getFileName(),
                    element.getLineNumber() > 0 ? element.getLineNumber() : null,
                    null,
                    inApp(element.getClassName())));
        }
        return frames;
    }

    /** 패키지 목록이 없으면 판정하지 않고 worker 에 맡긴다(6.3). */
    private Boolean inApp(String className) {
        if (options.inAppPackages().isEmpty()) {
            return null;
        }
        for (String pkg : options.inAppPackages()) {
            if (className.startsWith(pkg) && (className.length() == pkg.length() || className.charAt(pkg.length()) == '.')) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE);
    }
}
