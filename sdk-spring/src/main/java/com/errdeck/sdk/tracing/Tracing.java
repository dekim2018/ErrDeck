package com.errdeck.sdk.tracing;

import java.util.function.Supplier;

/**
 * 감시 대상 코드가 쓰는 추적 API 와 요청 스레드의 현재 트랜잭션(8.3 의 사용자 정의 구간).
 *
 * <pre>
 * try (var span = Tracing.span("payment", "결제사 승인")) {
 *     gateway.approve(order);
 * }
 * </pre>
 *
 * 진행 중인 트랜잭션이 없으면 아무것도 하지 않는다.
 */
public final class Tracing {

    private static final ThreadLocal<ActiveTransaction> CURRENT = new ThreadLocal<>();
    private static final Scope NOOP = () -> { };

    private Tracing() {
    }

    public static ActiveTransaction current() {
        return CURRENT.get();
    }

    public static String currentTraceId() {
        ActiveTransaction tx = CURRENT.get();
        return tx == null ? null : tx.traceId();
    }

    static void set(ActiveTransaction tx) {
        if (tx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tx);
        }
    }

    /** 구간 하나를 연다. try-with-resources 로 닫는다. */
    public static Scope span(String op, String description) {
        ActiveTransaction tx = CURRENT.get();
        if (tx == null) {
            return NOOP;
        }
        ActiveTransaction.OpenSpan span = tx.startSpan(op, description);
        return () -> tx.finishSpan(span);
    }

    @SuppressWarnings("try")
    public static <T> T span(String op, String description, Supplier<T> work) {
        try (Scope ignored = span(op, description)) {
            return work.get();
        }
    }

    /** 예외를 던지지 않는 닫기. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
