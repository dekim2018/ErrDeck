package com.errdeck.sdk.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.core.event.Transaction;
import com.errdeck.sdk.transport.Transport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TracingUnitTest {

    @Test
    void traceparent_를_읽고_쓴다() {
        TraceParent parsed = TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat(parsed.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(parsed.spanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(parsed.sampled()).isTrue();
        assertThat(parsed.format()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(TraceParent.parse("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")).isNull();
        assertThat(TraceParent.parse("garbage")).isNull();
        assertThat(TraceParent.newTraceId()).matches("[0-9a-f]{32}");
    }

    @Test
    void 앞_서비스의_trace_id_와_표본_여부를_이어받는다() {
        TraceParent incoming = new TraceParent("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false);
        ActiveTransaction tx = new ActiveTransaction(incoming, true, "GET /a", "http.server", java.time.Instant.now());

        assertThat(tx.traceId()).isEqualTo(incoming.traceId());
        assertThat(tx.sampled()).isFalse();
        assertThat(tx.toTransaction("200", false, null, null).parentSpanId()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void 연속으로_반복된_스팬은_하나로_접고_횟수를_남긴다() {
        ActiveTransaction tx = new ActiveTransaction(null, true, "GET /a", "http.server", java.time.Instant.now());
        for (int i = 0; i < 12; i++) {
            tx.finishSpan(tx.startSpan("db.sql", "SELECT * FROM t WHERE id = ?"));
        }
        tx.finishSpan(tx.startSpan("db.sql", "SELECT 1"));

        List<Transaction.Span> spans = tx.toTransaction("200", false, null, null).spans();
        assertThat(spans).hasSize(2);
        assertThat(spans.getFirst().repeat()).isEqualTo(12);
    }

    @Test
    void 스팬은_500개까지만_남긴다() {
        ActiveTransaction tx = new ActiveTransaction(null, true, "GET /a", "http.server", java.time.Instant.now());
        for (int i = 0; i < 600; i++) {
            tx.finishSpan(tx.startSpan("db.sql", "SELECT " + i));
        }

        assertThat(tx.toTransaction("200", false, null, null).spans()).hasSize(Transaction.MAX_SPANS);
        assertThat(tx.dropped()).isEqualTo(100);
    }

    @Test
    void 안쪽_스팬의_부모는_열려_있는_스팬이다() {
        ActiveTransaction tx = new ActiveTransaction(null, true, "GET /a", "http.server", java.time.Instant.now());
        ActiveTransaction.OpenSpan outer = tx.startSpan("custom", "바깥");
        ActiveTransaction.OpenSpan inner = tx.startSpan("db.sql", "SELECT 1");
        tx.finishSpan(inner);
        tx.finishSpan(outer);

        assertThat(inner.parentId()).isEqualTo(outer.id());
        assertThat(outer.parentId()).isEqualTo(tx.spanId());
    }

    @Test
    void SQL_의_값을_지운다() {
        assertThat(TracingDataSource.scrub("select * from users where id = 42 and name = 'kim' and x in (?, ?, ?)"))
                .isEqualTo("select * from users where id = ? and name = ? and x in (?)");
    }

    @Test
    void 뽑히지_않은_요청은_에러나_느림일_때만_부분_추적으로_남긴다() throws Exception {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        Transport transport = envelope -> {
            sent.add(envelope);
            return new Transport.Response(202, null);
        };
        ErrDeckTracer.Options options = new ErrDeckTracer.Options("test", "r1", 0.0, Duration.ofMillis(50), true, 100, 50,
                Duration.ofMillis(50), Duration.ofSeconds(1));
        try (ErrDeckTracer tracer = new ErrDeckTracer(options, transport, Clock.systemUTC())) {
            tracer.finish(tracer.start(null, "GET /ok", "http.server"), "GET /ok", "200", false);
            tracer.finish(tracer.start(null, "GET /fail", "http.server"), "GET /fail", "500", true);
            ActiveTransaction slow = tracer.start(null, "GET /slow", "http.server");
            Thread.sleep(80);
            tracer.finish(slow, "GET /slow", "200", false);
            assertThat(Tracing.current()).isNull();
        }

        var codec = new com.errdeck.core.envelope.EnvelopeCodec(com.errdeck.core.envelope.EnvelopeLimits.DEFAULT);
        List<Transaction> got = sent.stream().flatMap(b -> codec.decode(b).items().stream())
                .filter(i -> i.type() == com.errdeck.core.envelope.ItemType.TRANSACTION)
                .map(i -> Transaction.fromJson(i.payload())).toList();
        assertThat(got).extracting(Transaction::name).containsExactlyInAnyOrder("GET /fail", "GET /slow");
        assertThat(got).allMatch(Transaction::partial);
    }
}
