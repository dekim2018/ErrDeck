package com.errdeck.worker.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.Gzip;
import com.errdeck.core.fingerprint.AppFrames;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvelopeProcessorTest {

    private final EnvelopeProcessor processor = new EnvelopeProcessor(new EnvelopeCodec(EnvelopeLimits.DEFAULT),
            new Fingerprinter(new AppFrames(List.of("com.livebroadcast"))), new WorkerMetrics(new SimpleMeterRegistry()));

    @Test
    void 예제_봉투의_이벤트_2건을_지문과_함께_행으로_바꾸고_트랜잭션과_client_report_는_건너뛴다() {
        Instant received = Instant.parse("2026-11-24T10:00:03Z");
        QueueEntry entry = new QueueEntry(QueueStream.EVENTS, "1-0", 42, received,
                Gzip.compress(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE)));

        ProcessedEntry processed = processor.process(entry);

        assertThat(processed.rows()).hasSize(2);
        EventRow server = processed.rows().getFirst();
        assertThat(server.projectId()).isEqualTo(42);
        assertThat(server.fingerprint()).matches("[0-9a-f]{32}");
        assertThat(server.excType()).isEqualTo("org.springframework.dao.DataAccessResourceFailureException");
        assertThat(server.message()).isEqualTo("결제 요청 처리 중 오류");
        assertThat(server.receivedAt()).isEqualTo(received);
        assertThat(server.userHash()).isPositive();
        assertThat(server.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(server.payload()).contains("\"event_id\":\"0f3c2a5e-8b1d-4c7a-9e2f-6a4b8c1d2e3f\"");
        EventRow browser = processed.rows().get(1);
        assertThat(browser.message()).isEqualTo("Cannot read properties of undefined (reading 'ops')");
        assertThat(browser.userHash()).isZero();
        assertThat(browser.traceId()).isEmpty();
    }

    @Test
    void 읽을_수_없는_봉투는_행_없이_넘기고_센다() {
        QueueEntry broken = new QueueEntry(QueueStream.EVENTS, "1-0", 42, Instant.now(), new byte[] {1, 2, 3});

        ProcessedEntry processed = processor.process(broken);

        assertThat(processed.rows()).isEmpty();
        assertThat(processor.unreadable()).isEqualTo(1);
    }

    @Test
    void 사용자_해시는_같은_사용자면_같고_음수가_아니다() {
        assertThat(EnvelopeProcessor.userHash("user-1")).isEqualTo(EnvelopeProcessor.userHash("user-1"));
        assertThat(EnvelopeProcessor.userHash("user-1")).isNotEqualTo(EnvelopeProcessor.userHash("user-2"));
        for (int i = 0; i < 1000; i++) {
            assertThat(EnvelopeProcessor.userHash("u" + i)).isPositive();
        }
        assertThat(EnvelopeProcessor.userHash(null)).isZero();
    }
}
