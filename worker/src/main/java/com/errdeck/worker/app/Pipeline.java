package com.errdeck.worker.app;

import com.errdeck.worker.config.WorkerProperties;
import com.errdeck.worker.domain.EntryRef;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.Quarantine;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * worker 의 단계 구조(14.5).
 *
 * <pre>
 * 읽기 스레드(스트림당 1) -> 넘겨주기 큐(스트림당 2,000) -> 계산 풀(2) -> 쓰기 큐(10,000) -> 쓰기 스레드(1)
 * </pre>
 *
 * <p>모든 큐에 길이가 있으므로 막힘이 뒤에서 앞으로 전해진다. ClickHouse 가 느려지면 쓰기 스레드가 늦어지고,
 * 쓰기 큐가 차서 계산이 멈추고, 넘겨주기 큐가 차서 읽기가 멈춘다. 그러면 Redis 대기열이 길어져 ingest 가 503 을 준다.
 * 어느 단계에도 길이 제한 없는 공간이 없으므로 ClickHouse 가 오래 멈춰도 메모리가 계속 늘지 않는다(adr/0010).
 *
 * <p>확인 처리는 쓰기에 성공한 뒤에만 한다. 그 전에 죽으면 항목은 대기열에 남고 다른 worker 가 이어받는다(6.7).
 */
public class Pipeline {

    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);
    private static final long POLL_MILLIS = 50;
    /** 종료 제한 시간 안에 스레드를 정리할 여유. 스프링의 종료 단계 제한과 같은 값을 쓰므로 조금 먼저 포기한다. */
    private static final Duration SHUTDOWN_MARGIN = Duration.ofSeconds(3);

    private final QueueSource source;
    private final BatchWriter writer;
    private final EnvelopeProcessor processor;
    private final WorkerMetrics metrics;
    private final WorkerProperties properties;
    private final Clock clock;

    private final Map<QueueStream, BlockingQueue<QueueEntry>> handoff = new EnumMap<>(QueueStream.class);
    private final BlockingQueue<ProcessedEntry> writeQueue;
    /** 읽었지만 아직 확인 처리하지 않은 항목. 이어받기가 자기 손에 있는 항목을 두 번 넣지 않게 한다. */
    private final Set<EntryRef> inFlight = ConcurrentHashMap.newKeySet();

    private final List<Thread> readers = new ArrayList<>();
    private final List<Thread> computers = new ArrayList<>();
    private Thread writerThread;
    private CountDownLatch readersDone;
    private CountDownLatch computersDone;

    private volatile boolean reading;
    private volatile boolean computing;
    private volatile Instant giveUpAt = Instant.MAX;

    public Pipeline(QueueSource source, BatchWriter writer, EnvelopeProcessor processor, WorkerMetrics metrics,
                    WorkerProperties properties, Clock clock) {
        this.source = source;
        this.writer = writer;
        this.processor = processor;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        for (QueueStream stream : QueueStream.values()) {
            handoff.put(stream, new ArrayBlockingQueue<>(properties.handoffCapacity()));
        }
        this.writeQueue = new ArrayBlockingQueue<>(properties.writeQueueCapacity());
    }

    public synchronized void start() {
        reading = true;
        computing = true;
        readersDone = new CountDownLatch(QueueStream.values().length);
        computersDone = new CountDownLatch(properties.computeThreads());
        for (QueueStream stream : QueueStream.values()) {
            readers.add(Thread.ofPlatform().name("errdeck-reader-" + stream.name().toLowerCase(java.util.Locale.ROOT))
                    .start(() -> readLoop(stream)));
        }
        for (int i = 0; i < properties.computeThreads(); i++) {
            computers.add(Thread.ofPlatform().name("errdeck-compute-" + i).start(this::computeLoop));
        }
        writerThread = Thread.ofPlatform().name("errdeck-writer").start(this::writeLoop);
    }

    /**
     * 읽기를 멈추고, 이미 읽은 항목을 계산해 쓰고 확인 처리한 뒤 끝낸다(17.6). 제한 시간을 넘기면 남은 항목은
     * 확인 처리하지 않고 둔다. 대기열에 남아 다른 worker 나 다음 실행이 이어받는다.
     */
    public synchronized void stop() {
        giveUpAt = clock.instant().plus(properties.shutdownTimeout()).minus(SHUTDOWN_MARGIN);
        reading = false;
        awaitQuietly(readersDone);
        computing = false;
        awaitQuietly(computersDone);
        join(writerThread);
        for (Thread thread : allThreads()) {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
        log.info("worker 를 멈췄다. 확인 처리하지 못한 항목 {}건은 대기열에 남는다", inFlight.size());
    }

    public int inFlight() {
        return inFlight.size();
    }

    private void readLoop(QueueStream stream) {
        BlockingQueue<QueueEntry> queue = handoff.get(stream);
        Instant nextClaim = clock.instant().plus(properties.claimInterval());
        try {
            while (reading) {
                try {
                    if (!clock.instant().isBefore(nextClaim)) {
                        nextClaim = clock.instant().plus(properties.claimInterval());
                        enqueue(queue, source.claim(stream, properties.claimMinIdle(), properties.readCount()));
                    }
                    enqueue(queue, source.read(stream, properties.readCount(), properties.readBlock()));
                } catch (RuntimeException e) {
                    log.warn("{} 대기열을 읽지 못했다: {}", stream, e.toString());
                    sleep(Duration.ofSeconds(1));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            readersDone.countDown();
        }
    }

    /** 넘겨주기 큐가 차면 여기서 멈춘다. 멈춘 동안 대기열을 더 읽지 않는다. */
    private void enqueue(BlockingQueue<QueueEntry> queue, List<QueueEntry> entries) throws InterruptedException {
        for (QueueEntry entry : entries) {
            if (!inFlight.add(entry.ref())) {
                continue;
            }
            while (!queue.offer(entry, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (!reading) {
                    // 종료 중이라 넣지 못한 항목은 확인 처리하지 않고 대기열에 둔다.
                    inFlight.remove(entry.ref());
                    break;
                }
            }
        }
    }

    private void computeLoop() {
        FairPicker picker = new FairPicker(handoff.get(QueueStream.EVENTS), handoff.get(QueueStream.TX),
                properties.eventsPerTransaction());
        try {
            while (true) {
                QueueEntry entry = picker.next(POLL_MILLIS);
                if (entry == null) {
                    if (!computing && handoffEmpty()) {
                        return;
                    }
                    continue;
                }
                ProcessedEntry processed = processWithRetry(entry);
                // 쓰기 큐가 차면 여기서 멈춘다. 계산이 멈추면 넘겨주기 큐가 차고 읽기가 멈춘다.
                while (!writeQueue.offer(processed, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (pastGiveUp()) {
                        inFlight.remove(processed.ref());
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            computersDone.countDown();
        }
    }

    /**
     * 처리에 실패하면 다시 시도하고, 정해진 횟수만큼 실패하면 원문을 격리할 항목으로 바꾼다(6.7).
     * 해석할 수 없는 항목 하나가 대기열 전체를 막지 않게 한다.
     */
    private ProcessedEntry processWithRetry(QueueEntry entry) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= properties.maxProcessAttempts(); attempt++) {
            try {
                return processor.process(entry);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        log.warn("항목 {} 을 {}번 처리하지 못해 격리한다: {}", entry.id(), properties.maxProcessAttempts(), last.toString());
        return new ProcessedEntry(entry.ref(), entry.receivedAt(), List.of(),
                List.of(new Quarantine(entry.projectId(), entry.body(), "처리 실패: " + last.getMessage())));
    }

    private void writeLoop() {
        List<ProcessedEntry> batch = new ArrayList<>();
        int rows = 0;
        Instant batchStart = null;
        try {
            while (true) {
                ProcessedEntry entry = writeQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    if (batch.isEmpty()) {
                        batchStart = clock.instant();
                    }
                    batch.add(entry);
                    rows += entry.rows().size();
                }
                boolean upstreamDone = computersDone.getCount() == 0 && writeQueue.isEmpty();
                boolean full = rows >= properties.batchMaxRows();
                boolean old = batchStart != null && !clock.instant().isBefore(batchStart.plus(properties.batchMaxWait()));
                if (!batch.isEmpty() && (full || old || upstreamDone)) {
                    if (!flush(batch)) {
                        return;
                    }
                    batch = new ArrayList<>();
                    rows = 0;
                    batchStart = null;
                }
                if (upstreamDone && batch.isEmpty()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 묶음을 쓰고 확인 처리한다. 쓰기에 실패하면 간격을 늘려 가며 같은 쓰기를 이어서 한다. 그동안 쓰기 큐를 비우지 않으므로
     * 막힘이 앞 단계로 전해진다. 종료 제한 시간을 넘기면 확인 처리하지 않고 포기한다.
     *
     * @return 계속할 수 있으면 true
     */
    private boolean flush(List<ProcessedEntry> batch) throws InterruptedException {
        BatchWriter.BatchWrite write = writer.prepare(batch);
        Duration backoff = Duration.ofMillis(500);
        while (true) {
            long start = System.nanoTime();
            try {
                write.run();
                metrics.stage(WorkerMetrics.Stage.WRITE, System.nanoTime() - start);
                break;
            } catch (RuntimeException e) {
                if (pastGiveUp()) {
                    log.warn("종료 제한 시간이 지나 묶음 {}건을 쓰지 못하고 포기한다. 확인 처리하지 않았으므로 대기열에 남는다", batch.size());
                    batch.forEach(entry -> inFlight.remove(entry.ref()));
                    return false;
                }
                log.warn("묶음 {}건을 쓰지 못했다. {} 뒤 이어서 쓴다: {}", batch.size(), backoff, e.toString());
                sleep(backoff);
                backoff = min(backoff.multipliedBy(2), properties.retryMaxBackoff());
            }
        }

        List<EntryRef> refs = batch.stream().map(ProcessedEntry::ref).toList();
        try {
            source.acknowledgeAll(refs);
        } catch (RuntimeException e) {
            // 쓰기는 끝났으므로 다시 쓰지 않는다. 확인 처리하지 못한 항목은 이어받기로 다시 쓰이고,
            // 같은 event_id 는 ReplacingMergeTree 가 병합 때 하나로 합친다(6.7).
            log.warn("묶음 {}건을 확인 처리하지 못했다: {}", refs.size(), e.toString());
        }
        Instant now = clock.instant();
        for (ProcessedEntry entry : batch) {
            inFlight.remove(entry.ref());
            if (entry.receivedAt() != null) {
                metrics.endToEnd(Duration.between(entry.receivedAt(), now));
            }
        }
        return true;
    }

    private boolean handoffEmpty() {
        return handoff.values().stream().allMatch(BlockingQueue::isEmpty);
    }

    private boolean pastGiveUp() {
        return clock.instant().isAfter(giveUpAt);
    }

    private List<Thread> allThreads() {
        List<Thread> all = new ArrayList<>(readers);
        all.addAll(computers);
        if (writerThread != null) {
            all.add(writerThread);
        }
        return all;
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            long millis = Math.max(0, Duration.between(clock.instant(), giveUpAt).toMillis());
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(1, Duration.between(clock.instant(), giveUpAt.plus(SHUTDOWN_MARGIN)).toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
