package com.errdeck.worker.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeHeader;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.StackFrame;
import com.errdeck.core.fingerprint.AppFrames;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.worker.config.WorkerProperties;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import com.errdeck.worker.support.FakeEventSink;
import com.errdeck.worker.support.FakeQueueSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PipelineTest {

    private static final EnvelopeCodec CODEC = new EnvelopeCodec(EnvelopeLimits.DEFAULT);

    private final FakeQueueSource source = new FakeQueueSource();
    private final FakeEventSink sink = new FakeEventSink();
    private Pipeline pipeline;

    @AfterEach
    void stop() {
        if (pipeline != null) {
            pipeline.stop();
        }
    }

    @Test
    void 읽은_항목의_이벤트를_전부_쓰고_전부_확인_처리한다() {
        for (int i = 0; i < 300; i++) {
            source.add(QueueStream.EVENTS, () -> envelope(2));
            source.add(QueueStream.TX, () -> transactionEnvelope());
        }

        start(properties(5000, Duration.ofMillis(100)));

        await().atMost(Duration.ofSeconds(10)).until(() -> source.acked.size() == 600 && pipeline.inFlight() == 0);
        assertThat(sink.rows()).isEqualTo(600);
    }

    @Test
    void 쓰기에_성공하기_전에는_확인_처리하지_않고_실패한_묶음을_다시_쓴다() throws Exception {
        sink.down = true;
        for (int i = 0; i < 10; i++) {
            source.add(QueueStream.EVENTS, () -> envelope(1));
        }

        start(properties(5000, Duration.ofMillis(50)));
        Thread.sleep(1_500);
        assertThat(source.acked).isEmpty();
        assertThat(pipeline.inFlight()).isEqualTo(10);

        sink.down = false;
        await().atMost(Duration.ofSeconds(10)).until(() -> source.acked.size() == 10);
        assertThat(sink.rows()).isEqualTo(10);
    }

    @Test
    void 묶음은_최대_행_수에서_자르고_남은_것은_시간이_지나면_쓴다() {
        for (int i = 0; i < 25; i++) {
            source.add(QueueStream.EVENTS, () -> envelope(1));
        }

        start(properties(10, Duration.ofMillis(300)));

        await().atMost(Duration.ofSeconds(5)).until(() -> sink.rows() == 25);
        assertThat(sink.batches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(10));
        assertThat(sink.batches.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void 저장소가_멈추면_큐의_길이만큼만_읽고_더_읽지_않는다() throws Exception {
        sink.down = true;
        for (int i = 0; i < 20_000; i++) {
            source.add(QueueStream.EVENTS, () -> envelope(1));
        }
        WorkerProperties props = properties(50, Duration.ofMillis(50), 100, 200);

        start(props);
        Thread.sleep(2_000);
        long readAfter2s = source.read.get();
        Thread.sleep(1_000);

        // 넘겨주기 큐 100 + 쓰기 큐 200 + 쓰기 스레드가 붙잡은 묶음 + 계산 중인 것 + 한 번에 읽는 500 을 넘지 않는다.
        assertThat(readAfter2s).isLessThan(100 + 200 + 60 + 2 + 500);
        assertThat(source.read.get()).isEqualTo(readAfter2s);
        assertThat(source.acked).isEmpty();
    }

    @Test
    void 멈출_때_이미_읽은_항목을_쓰고_확인_처리한다() {
        for (int i = 0; i < 50; i++) {
            source.add(QueueStream.EVENTS, () -> envelope(1));
        }
        // 묶음을 오래 모으도록 두어, 멈추는 시점에 쓰지 않은 묶음이 남게 한다.
        start(properties(5000, Duration.ofSeconds(60)));
        await().atMost(Duration.ofSeconds(5)).until(() -> source.read.get() == 50);

        pipeline.stop();
        pipeline = null;

        assertThat(sink.rows()).isEqualTo(50);
        assertThat(source.acked).hasSize(50);
    }

    @Test
    void 저장소가_멈춘_채로_종료하면_제한_시간_안에_끝나고_확인_처리하지_않는다() {
        sink.down = true;
        for (int i = 0; i < 10; i++) {
            source.add(QueueStream.EVENTS, () -> envelope(1));
        }
        WorkerProperties props = withShutdown(properties(5000, Duration.ofMillis(50)), Duration.ofSeconds(5));
        start(props);
        await().atMost(Duration.ofSeconds(5)).until(() -> source.read.get() == 10);

        long start = System.nanoTime();
        pipeline.stop();
        pipeline = null;

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
        assertThat(source.acked).isEmpty();
    }

    @Test
    void 이어받은_항목이_이미_처리_중이면_두_번_넣지_않는다() {
        sink.down = true;
        QueueEntry entry = source.add(QueueStream.EVENTS, () -> envelope(1));
        source.addClaimable(entry);
        WorkerProperties props = properties(5000, Duration.ofMillis(50));
        start(withClaimInterval(props, Duration.ofMillis(100)));
        await().atMost(Duration.ofSeconds(5)).until(() -> source.read.get() == 1);

        sink.down = false;
        await().atMost(Duration.ofSeconds(10)).until(() -> source.acked.size() == 1);

        assertThat(sink.rows()).isEqualTo(1);
    }

    private void start(WorkerProperties properties) {
        WorkerMetrics metrics = new WorkerMetrics(new SimpleMeterRegistry());
        EnvelopeProcessor processor = new EnvelopeProcessor(CODEC, new Fingerprinter(new AppFrames(List.of("com.app"))), metrics);
        pipeline = new Pipeline(source, sink, processor, metrics, properties, Clock.systemUTC());
        pipeline.start();
    }

    static WorkerProperties properties(int batchMaxRows, Duration batchMaxWait) {
        return properties(batchMaxRows, batchMaxWait, 2000, 10_000);
    }

    static WorkerProperties properties(int batchMaxRows, Duration batchMaxWait, int handoff, int writeQueue) {
        return new WorkerProperties("g", 500, Duration.ofMillis(20), handoff, 2, 3, writeQueue, batchMaxRows, batchMaxWait,
                Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofMillis(200), Duration.ofSeconds(30), List.of());
    }

    private static WorkerProperties withShutdown(WorkerProperties p, Duration timeout) {
        return new WorkerProperties(p.consumerGroup(), p.readCount(), p.readBlock(), p.handoffCapacity(), p.computeThreads(),
                p.eventsPerTransaction(), p.writeQueueCapacity(), p.batchMaxRows(), p.batchMaxWait(), p.claimInterval(),
                p.claimMinIdle(), p.retryMaxBackoff(), timeout, p.inAppPackages());
    }

    private static WorkerProperties withClaimInterval(WorkerProperties p, Duration interval) {
        return new WorkerProperties(p.consumerGroup(), p.readCount(), p.readBlock(), p.handoffCapacity(), p.computeThreads(),
                p.eventsPerTransaction(), p.writeQueueCapacity(), p.batchMaxRows(), p.batchMaxWait(), interval,
                p.claimMinIdle(), p.retryMaxBackoff(), p.shutdownTimeout(), p.inAppPackages());
    }

    static byte[] envelope(int events) {
        List<EnvelopeItem> items = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            Event event = new Event(UUID.randomUUID(), Instant.now(), "error", null, "test", "r1", "u" + i, null, List.of(), 1,
                    List.of(new ExceptionInfo("E", "x", List.of(new StackFrame("com.app.Svc", "run", "Svc.java", 1, null, null)))),
                    null, Map.of(), List.of());
            items.add(new EnvelopeItem(ItemType.EVENT, event.toJson()));
        }
        return CODEC.encode(new Envelope(new EnvelopeHeader("test", Instant.now()), items));
    }

    static byte[] transactionEnvelope() {
        return CODEC.encode(new Envelope(new EnvelopeHeader("test", Instant.now()),
                List.of(new EnvelopeItem(ItemType.TRANSACTION, "{\"name\":\"GET /\"}".getBytes()))));
    }
}
