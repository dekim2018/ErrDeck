package com.errdeck.sdk.tracing;

import com.errdeck.core.event.Transaction;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 진행 중인 요청 하나의 기록. 요청 스레드에서만 쓰지만, 같은 요청이 다른 스레드로 넘긴 일에서 스팬이 들어올 수 있어 잠근다.
 *
 * <p>표본으로 뽑히지 않은 요청도 스팬을 모은다. 끝에서 에러나 느림이면 남기기 위해서다(8.4).
 */
public final class ActiveTransaction {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final boolean sampled;
    private final Instant start;
    private final long startNanos;
    private final String op;
    private final List<Transaction.Span> spans = new ArrayList<>();
    private final Deque<String> open = new ArrayDeque<>();
    private String name;
    private int dropped;

    ActiveTransaction(TraceParent incoming, boolean sampleNew, String name, String op, Instant start) {
        this.traceId = incoming == null ? TraceParent.newTraceId() : incoming.traceId();
        this.parentSpanId = incoming == null ? null : incoming.spanId();
        this.sampled = incoming == null ? sampleNew : incoming.sampled();
        this.spanId = TraceParent.newSpanId();
        this.name = name;
        this.op = op;
        this.start = start;
        this.startNanos = System.nanoTime();
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public boolean sampled() {
        return sampled;
    }

    public String op() {
        return op;
    }

    /** 뒤 서비스로 넘길 헤더. 지금 열린 스팬이 있으면 그것이 부모다. */
    public synchronized String traceparent(String spanId) {
        return new TraceParent(traceId, spanId, sampled).format();
    }

    /** 경로 패턴은 요청을 처리한 뒤에야 알 수 있어 끝에서 이름을 바꾼다. */
    public synchronized void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    /** 스팬을 연다. 돌려준 ID 로 {@link #finishSpan} 을 부른다. */
    public synchronized OpenSpan startSpan(String op, String description) {
        String id = TraceParent.newSpanId();
        String parent = open.isEmpty() ? spanId : open.peek();
        open.push(id);
        return new OpenSpan(id, parent, op, description, System.nanoTime());
    }

    /**
     * 스팬을 닫는다. 바로 앞 스팬과 종류, 설명, 부모가 같으면 새로 남기지 않고 횟수와 시간을 더한다(8.5).
     * 반복문 안의 쿼리가 스팬 수천 개가 되지 않게 하고, 그 횟수로 반복 쿼리를 찾는다.
     */
    public synchronized void finishSpan(OpenSpan span) {
        open.remove(span.id());
        long offset = Math.max(0, (span.startNanos() - startNanos) / 1_000_000);
        long duration = Math.max(0, (System.nanoTime() - span.startNanos()) / 1_000_000);
        if (!spans.isEmpty()) {
            Transaction.Span last = spans.getLast();
            if (last.op().equals(span.op()) && last.description().equals(span.description())
                    && Objects.equals(last.parentSpanId(), span.parentId())) {
                spans.set(spans.size() - 1, new Transaction.Span(last.spanId(), last.parentSpanId(), last.op(), last.description(),
                        last.startOffsetMs(), last.durationMs() + duration, last.repeat() + 1));
                return;
            }
        }
        if (spans.size() >= Transaction.MAX_SPANS) {
            dropped++;
            return;
        }
        spans.add(new Transaction.Span(span.id(), span.parentId(), span.op(), span.description(), offset, duration, 1));
    }

    long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    int dropped() {
        return dropped;
    }

    synchronized Transaction toTransaction(String status, boolean partial, String environment, String release) {
        return new Transaction(traceId, spanId, parentSpanId, name, op, start, elapsedMs(), status, environment, release, partial,
                List.copyOf(spans));
    }

    public record OpenSpan(String id, String parentId, String op, String description, long startNanos) {
    }
}
