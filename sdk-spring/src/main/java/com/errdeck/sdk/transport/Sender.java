package com.errdeck.sdk.transport;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeHeader;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.ClientReport;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 전송 스레드. 큐에서 2초마다 또는 50건이 모이면 꺼내 이벤트로 바꾸고, 압축해 보낸다(11.2).
 *
 * <p>일의 절반이 직렬화와 압축이라 가상 스레드가 아닌 일반 데몬 스레드 하나로 둔다. 하나로 고정하면 SDK 가 쓰는 CPU 가
 * 코어 하나를 넘지 못한다. ingest 가 429, 503 을 주면 Retry-After 동안 보내지 않고, 그동안 생긴 것은 버리고 센다.
 *
 * @param <T> 큐에 든 것. 이벤트로 바꾸는 방법과 대표하는 횟수를 함께 받는다
 */
public final class Sender<T> {

    public static final String SDK = "spring/0.1.0";
    /** Retry-After 가 없는 429, 503 에서 쉬는 시간. */
    static final Duration DEFAULT_PAUSE = Duration.ofSeconds(60);
    private static final long WAIT_SLICE_NANOS = Duration.ofMillis(100).toNanos();

    private final BlockingQueue<T> queue;
    private final Supplier<List<T>> extra;
    private final Function<T, EnvelopeItem> toItem;
    private final ToLongFunction<T> weight;
    private final Transport transport;
    private final Discards discards;
    private final Clock clock;
    private final int batchSize;
    private final Duration flushInterval;
    private final EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);

    private volatile long pausedUntilNanos = Long.MIN_VALUE;
    private volatile DiscardReason pauseReason;
    private volatile boolean disabled;

    /**
     * @param extra 큐 말고 따로 보낼 것. 같은 에러를 접은 대표 건이다
     */
    public Sender(BlockingQueue<T> queue, Supplier<List<T>> extra, Function<T, EnvelopeItem> toItem, ToLongFunction<T> weight,
                  Transport transport, Discards discards, Clock clock, int batchSize, Duration flushInterval) {
        this.queue = queue;
        this.extra = extra;
        this.toItem = toItem;
        this.weight = weight;
        this.transport = transport;
        this.discards = discards;
        this.clock = clock;
        this.batchSize = batchSize;
        this.flushInterval = flushInterval;
    }

    /**
     * 묶음 하나를 모은다. 건수가 다 차거나 간격이 지나거나 {@code keepWaiting} 이 false 가 되면 돌아온다.
     * 종료 신호를 놓치지 않으려고 짧게 나눠 기다린다. 모은 것을 들고 기다리다 끊기면 그만큼 잃기 때문이다.
     */
    public List<T> collect(java.util.function.BooleanSupplier keepWaiting) throws InterruptedException {
        List<T> batch = new ArrayList<>(batchSize);
        long deadline = System.nanoTime() + flushInterval.toNanos();
        while (batch.size() < batchSize && keepWaiting.getAsBoolean()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            T item = queue.poll(Math.min(remaining, WAIT_SLICE_NANOS), TimeUnit.NANOSECONDS);
            if (item != null) {
                batch.add(item);
                queue.drainTo(batch, batchSize - batch.size());
            }
        }
        batch.addAll(extra.get());
        return batch;
    }

    /** 큐에 남은 것과 접힌 것을 모두 꺼낸다. 종료할 때 쓴다. */
    public List<T> drainAll() {
        List<T> all = new ArrayList<>();
        queue.drainTo(all);
        all.addAll(extra.get());
        return all;
    }

    /** 보낸다. 실패해도 예외를 던지지 않고 버린 건수로 남긴다. */
    public void send(List<T> batch) throws InterruptedException {
        long count = batch.stream().mapToLong(weight).sum();
        if (disabled) {
            discards.add(DiscardReason.UNAUTHORIZED, count);
            return;
        }
        if (System.nanoTime() < pausedUntilNanos) {
            discards.add(pauseReason, count);
            return;
        }
        ClientReport report = discards.drain(clock.instant());
        if (batch.isEmpty() && report == null) {
            return;
        }
        byte[] body;
        try {
            List<EnvelopeItem> items = new ArrayList<>(batch.size() + 1);
            for (T item : batch) {
                items.add(toItem.apply(item));
            }
            if (report != null) {
                items.add(new EnvelopeItem(ItemType.CLIENT_REPORT, report.toJson()));
            }
            body = codec.encode(new Envelope(new EnvelopeHeader(SDK, clock.instant()), items));
        } catch (RuntimeException e) {
            // 직렬화에서 실패한 것은 다시 해도 같으므로 버리고 센다.
            com.errdeck.sdk.InternalLog.warn("encode", "이벤트를 봉투로 만들지 못했다: " + e);
            discards.add(DiscardReason.NETWORK_ERROR, count);
            restore(report);
            return;
        }

        Transport.Response response = null;
        for (int attempt = 0; attempt < 2 && response == null; attempt++) {
            try {
                response = transport.send(body);
            } catch (IOException e) {
                if (attempt == 1) {
                    com.errdeck.sdk.InternalLog.warn("network", "ingest 에 보내지 못했다: " + e);
                }
            }
        }
        if (response == null) {
            discards.add(DiscardReason.NETWORK_ERROR, count);
            restore(report);
            return;
        }
        handle(response, count, report);
    }

    private void handle(Transport.Response response, long count, ClientReport report) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            return;
        }
        restore(report);
        switch (status) {
            case 429 -> pause(response, DiscardReason.RATE_LIMITED, count);
            case 503 -> pause(response, DiscardReason.QUEUE_FULL, count);
            case 413 -> discards.add(DiscardReason.TOO_LARGE, count);
            case 401, 403 -> {
                // 키나 출처가 틀렸다. 고쳐질 때까지 보내 봐야 소용없으므로 멈추고 한 번만 남긴다(5.4).
                disabled = true;
                discards.add(DiscardReason.UNAUTHORIZED, count);
                com.errdeck.sdk.InternalLog.warn("unauthorized", "ingest 가 " + status + " 로 거절해 전송을 멈춘다. DSN 을 확인한다");
            }
            default -> discards.add(DiscardReason.NETWORK_ERROR, count);
        }
    }

    private void pause(Transport.Response response, DiscardReason reason, long count) {
        Duration pause = response.retryAfterSeconds() == null ? DEFAULT_PAUSE : Duration.ofSeconds(response.retryAfterSeconds());
        pauseReason = reason;
        pausedUntilNanos = System.nanoTime() + pause.toNanos();
        discards.add(reason, count);
    }

    private void restore(ClientReport report) {
        if (report != null) {
            discards.restore(report);
        }
    }

    public boolean isDisabled() {
        return disabled;
    }
}
