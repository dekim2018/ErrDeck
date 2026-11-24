package com.errdeck.sdk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 같은 에러를 접는다(11.1). 같은 스택의 에러가 구간 안에 기준 건수를 넘으면 이후는 횟수만 세고,
 * 구간이 끝날 때 대표 1건에 횟수를 실어 보낸다. 감시 대상이 고장 나 같은 예외를 쏟아낼 때 네트워크와 큐를 지킨다(5.1).
 *
 * <p>요청 스레드가 부르므로 가볍게 둔다. 스택은 비교만 하고 변환하지 않는다. 기억하는 스택 종류에 상한을 둔다.
 */
final class Folder {

    /** 서로 다른 스택을 이만큼까지만 기억한다. 넘으면 접지 않고 그대로 보낸다. */
    static final int MAX_SIGNATURES = 1000;
    private static final int SIGNATURE_FRAMES = 20;

    private final Duration window;
    private final int threshold;
    private final Clock clock;
    private final Map<Signature, Window> windows = new ConcurrentHashMap<>();
    /** 새 구간으로 바뀔 때 밀려난 구간 중 접힌 것이 있는 것. 전송 스레드가 대표 건으로 꺼낸다. */
    private final ConcurrentLinkedQueue<Window> retired = new ConcurrentLinkedQueue<>();

    Folder(Duration window, int threshold, Clock clock) {
        this.window = window;
        this.threshold = threshold;
        this.clock = clock;
    }

    /** @return 그대로 보내면 true. false 면 대표 건에 횟수로만 더했다 */
    boolean admit(Throwable throwable, RequestContext request) {
        Signature signature = Signature.of(throwable);
        if (!windows.containsKey(signature) && windows.size() >= MAX_SIGNATURES) {
            return true;
        }
        Instant now = clock.instant();
        Window current = windows.compute(signature, (key, existing) -> {
            if (existing == null || existing.isOver(now, window)) {
                if (existing != null && existing.hasFolded()) {
                    retired.add(existing);
                }
                return new Window(now);
            }
            return existing;
        });
        return current.admit(threshold, throwable, request);
    }

    /** 구간이 끝난 것의 대표 건을 꺼낸다. 전송 스레드가 부른다. */
    List<Capture> drainFolded() {
        Instant now = clock.instant();
        List<Capture> out = new ArrayList<>();
        for (Map.Entry<Signature, Window> entry : windows.entrySet()) {
            Window w = entry.getValue();
            if (w.isOver(now, window) && windows.remove(entry.getKey(), w)) {
                w.representative(now).ifPresent(out::add);
            }
        }
        Window w;
        while ((w = retired.poll()) != null) {
            w.representative(now).ifPresent(out::add);
        }
        return out;
    }

    /** 예외 타입과 위쪽 프레임으로 만든 비교 열쇠. 줄 번호까지 같아야 같은 에러로 본다. */
    record Signature(List<Object> parts) {

        static Signature of(Throwable throwable) {
            List<Object> parts = new ArrayList<>();
            int depth = 0;
            for (Throwable t = throwable; t != null && depth < 5; t = t.getCause() == t ? null : t.getCause(), depth++) {
                parts.add(t.getClass());
                StackTraceElement[] trace = t.getStackTrace();
                for (int i = 0; i < Math.min(trace.length, SIGNATURE_FRAMES); i++) {
                    parts.add(trace[i]);
                }
            }
            return new Signature(List.copyOf(parts));
        }
    }

    private static final class Window {

        private final Instant start;
        private int passed;
        private long folded;
        private Throwable sample;
        private RequestContext sampleRequest;

        Window(Instant start) {
            this.start = start;
        }

        boolean isOver(Instant now, Duration window) {
            return !now.isBefore(start.plus(window));
        }

        synchronized boolean admit(int threshold, Throwable throwable, RequestContext request) {
            if (passed < threshold) {
                passed++;
                return true;
            }
            if (folded == 0) {
                sample = throwable;
                sampleRequest = request;
            }
            folded++;
            return false;
        }

        synchronized boolean hasFolded() {
            return folded > 0;
        }

        synchronized Optional<Capture> representative(Instant now) {
            if (folded == 0) {
                return Optional.empty();
            }
            Capture capture = new Capture(UUID.randomUUID(), now, sample, sampleRequest, folded);
            folded = 0;
            sample = null;
            sampleRequest = null;
            return Optional.of(capture);
        }
    }
}
