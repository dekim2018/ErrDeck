package com.errdeck.sdk.tracing;

import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Transaction;
import com.errdeck.sdk.InternalLog;
import com.errdeck.sdk.transport.DiscardReason;
import com.errdeck.sdk.transport.Discards;
import com.errdeck.sdk.transport.Sender;
import com.errdeck.sdk.transport.Transport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 트랜잭션을 시작하고, 끝에서 남길지 정하고, 남길 것을 따로 묶어 보낸다(8.4). 에러와 같은 원칙을 따른다.
 * 요청 스레드는 큐에 넣기만 하고 기다리지 않으며, 큐가 차면 버리고 센다(11.2).
 */
public final class ErrDeckTracer implements AutoCloseable {

    /**
     * @param sampleRate    요청이 시작될 때 표본으로 뽑을 비율. 앞 서비스가 정했으면 그것을 따른다
     * @param slowThreshold 뽑히지 않았어도 이보다 느리면 남긴다
     * @param tailSampling  false 면 끝에서 다시 판단하지 않는다. 뽑히지 않은 요청의 스팬을 모으지 않아 부담이 준다
     */
    public record Options(String environment, String release, double sampleRate, Duration slowThreshold, boolean tailSampling,
                          int queueCapacity, int batchSize, Duration flushInterval, Duration shutdownFlush) {

        public static Options defaults(String environment, String release) {
            return new Options(environment, release, 0.1, Duration.ofSeconds(1), true, 1000, 50, Duration.ofSeconds(5),
                    Duration.ofSeconds(2));
        }
    }

    private final Options options;
    private final Clock clock;
    private final BlockingQueue<Transaction> queue;
    private final Discards discards = new Discards();
    private final Sender<Transaction> sender;
    private final Thread thread;
    private volatile boolean running = true;

    public ErrDeckTracer(Options options, Transport transport, Clock clock) {
        this.options = options;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(options.queueCapacity());
        this.sender = new Sender<>(queue, List::of, tx -> new EnvelopeItem(ItemType.TRANSACTION, tx.toJson()), tx -> 1L, transport,
                discards, clock, options.batchSize(), options.flushInterval());
        this.thread = Thread.ofPlatform().daemon().name("errdeck-tx-sender").start(this::loop);
    }

    /**
     * 트랜잭션을 시작하고 현재 스레드에 둔다. 앞 서비스의 {@code traceparent} 가 있으면 같은 trace_id 와 표본 여부를 이어받는다.
     * 끝에서 남기지 않을 요청이면(뽑히지 않았고 끝에서 판단하지도 않음) null 이다.
     */
    public ActiveTransaction start(String traceparent, String name, String op) {
        ActiveTransaction tx = startDetached(traceparent, name, op);
        if (tx != null) {
            Tracing.set(tx);
        }
        return tx;
    }

    /** 스레드에 두지 않고 시작한다. 한 요청이 여러 스레드를 오가는 WebFlux 에서 쓴다. */
    public ActiveTransaction startDetached(String traceparent, String name, String op) {
        try {
            TraceParent incoming = TraceParent.parse(traceparent);
            boolean sampleNew = ThreadLocalRandom.current().nextDouble() < options.sampleRate();
            return new ActiveTransaction(incoming, sampleNew, name, op, clock.instant());
        } catch (RuntimeException e) {
            InternalLog.warn("tracing", "트랜잭션을 시작하지 못했다: " + e);
            return null;
        }
    }

    /**
     * 끝낸다. 뽑혔으면 보내고, 뽑히지 않았어도 에러이거나 느리면 이 서비스의 구간만 부분 추적으로 보낸다(8.4).
     *
     * @param status {@code ok}, {@code error} 또는 HTTP 상태 코드
     */
    public void finish(ActiveTransaction tx, String name, String status, boolean error) {
        Tracing.set(null);
        if (tx == null || !running) {
            return;
        }
        try {
            tx.rename(name);
            boolean slow = tx.elapsedMs() >= options.slowThreshold().toMillis();
            boolean keep = tx.sampled() || options.tailSampling() && (error || slow);
            if (!keep) {
                return;
            }
            Transaction transaction = tx.toTransaction(status, !tx.sampled(), options.environment(), options.release());
            if (!queue.offer(transaction)) {
                discards.add(DiscardReason.QUEUE_OVERFLOW, 1);
            }
        } catch (RuntimeException e) {
            InternalLog.warn("tracing", "트랜잭션을 끝내지 못했다: " + e);
        }
    }

    public Options options() {
        return options;
    }

    public Discards discards() {
        return discards;
    }

    private void loop() {
        try {
            while (running) {
                try {
                    sender.send(sender.collect(() -> running));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    InternalLog.warn("tx-sender", "트랜잭션 전송 스레드에서 문제가 생겼다: " + e);
                }
            }
        } finally {
            try {
                List<Transaction> rest = sender.drainAll();
                for (int from = 0; from < rest.size(); from += options.batchSize()) {
                    sender.send(rest.subList(from, Math.min(rest.size(), from + options.batchSize())));
                }
            } catch (Throwable e) {
                InternalLog.warn("tx-flush", "종료 중 남은 트랜잭션을 보내지 못했다: " + e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(options.shutdownFlush().plus(Duration.ofMillis(100)).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            thread.interrupt();
        }
    }
}
