package com.errdeck.sdk;

import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.sdk.tracing.Tracing;
import com.errdeck.sdk.transport.DiscardReason;
import com.errdeck.sdk.transport.Discards;
import com.errdeck.sdk.transport.Sender;
import com.errdeck.sdk.transport.Transport;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * SDK 의 중심. 요청 스레드는 {@link #capture} 로 큐에 넣기만 하고, 전송 스레드 하나가 꺼내 보낸다(11.1).
 *
 * <ul>
 *   <li>요청 스레드를 막지 않는다. 큐가 차면 기다리지 않고 버리고 센다.</li>
 *   <li>메모리가 늘지 않는다. 큐, 접기, 이미 잡은 예외 기록에 모두 상한이 있다.</li>
 *   <li>예외를 밖으로 던지지 않는다. 모든 진입점을 감싼다.</li>
 * </ul>
 */
public final class ErrDeckClient implements AutoCloseable {

    /** 같은 예외 객체를 여러 경로(리졸버, 필터, 로그)가 함께 잡아도 한 번만 보낸다. */
    private static final int RECENT_LIMIT = 1000;

    private final ErrDeckOptions options;
    private final Clock clock;
    private final BlockingQueue<Capture> queue;
    private final Folder folder;
    private final Discards discards = new Discards();
    private final Sender<Capture> sender;
    private final Map<Throwable, Boolean> recent = Collections.synchronizedMap(new WeakHashMap<>());
    private final Thread thread;
    private volatile boolean running = true;

    public ErrDeckClient(ErrDeckOptions options, Transport transport, Clock clock) {
        this.options = options;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(options.queueCapacity());
        this.folder = new Folder(options.foldWindow(), options.foldThreshold(), clock);
        EventBuilder builder = new EventBuilder(options);
        this.sender = new Sender<>(queue, folder::drainFolded,
                c -> new EnvelopeItem(ItemType.EVENT, builder.build(c).toJson()), Capture::count, transport, discards, clock,
                options.batchSize(), options.flushInterval());
        this.thread = Thread.ofPlatform().daemon().name("errdeck-sender").start(this::loop);
    }

    /** 예외를 보낼 큐에 넣는다. 어떤 경우에도 예외를 던지지 않고 기다리지 않는다. */
    public void capture(Throwable throwable, RequestContext request) {
        try {
            if (throwable == null || !running) {
                return;
            }
            if (recent.size() >= RECENT_LIMIT) {
                recent.clear();
            }
            if (recent.putIfAbsent(throwable, Boolean.TRUE) != null) {
                return;
            }
            if (!folder.admit(throwable, request)) {
                return;
            }
            Capture capture = new Capture(UUID.randomUUID(), clock.instant(), throwable, request, 1, Tracing.currentTraceId());
            if (!queue.offer(capture)) {
                discards.add(DiscardReason.QUEUE_OVERFLOW, 1);
            }
        } catch (Throwable e) {
            InternalLog.warn("capture", "예외를 큐에 넣지 못했다: " + e);
        }
    }

    public Discards discards() {
        return discards;
    }

    public ErrDeckOptions options() {
        return options;
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
                    InternalLog.warn("sender", "전송 스레드에서 문제가 생겼다: " + e);
                }
            }
        } finally {
            flushRemaining();
        }
    }

    /** 종료할 때 큐에 남은 것과 접힌 것을 보낸다. 제한 시간은 close() 가 지킨다. */
    private void flushRemaining() {
        try {
            List<Capture> rest = sender.drainAll();
            int size = options.batchSize();
            for (int from = 0; from < rest.size(); from += size) {
                sender.send(rest.subList(from, Math.min(rest.size(), from + size)));
            }
            if (rest.isEmpty()) {
                sender.send(List.of());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            InternalLog.warn("flush", "종료 중 남은 것을 보내지 못했다: " + e);
        }
    }

    /** 새로 받지 않고, 남은 것을 최대 {@link ErrDeckOptions#shutdownFlush()} 동안 보낸 뒤 끝낸다(11.2). */
    @Override
    public void close() {
        ErrDeck.uninstall(this);
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
