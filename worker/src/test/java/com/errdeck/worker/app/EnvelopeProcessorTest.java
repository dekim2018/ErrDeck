package com.errdeck.worker.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeException;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.Gzip;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.fingerprint.AppFrames;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
        assertThat(server.count()).isEqualTo(7);
        assertThat(server.culprit()).isEqualTo("com.livebroadcast.interaction.payment.PaymentService#charge");
        assertThat(server.title()).isEqualTo("org.springframework.dao.DataAccessResourceFailureException: 결제 요청 처리 중 오류");
        assertThat(server.payload()).contains("\"event_id\":\"0f3c2a5e-8b1d-4c7a-9e2f-6a4b8c1d2e3f\"");
        EventRow browser = processed.rows().get(1);
        assertThat(browser.message()).isEqualTo("Cannot read properties of undefined (reading 'ops')");
        assertThat(browser.userHash()).isZero();
        assertThat(browser.traceId()).isEmpty();
    }

    @Test
    void 읽을_수_없는_봉투는_예외를_던져_단계_구조가_다시_시도하게_한다() {
        QueueEntry broken = new QueueEntry(QueueStream.EVENTS, "1-0", 42, Instant.now(), new byte[] {1, 2, 3});

        assertThatThrownBy(() -> processor.process(broken)).isInstanceOf(EnvelopeException.class);
    }

    @Test
    void 봉투_안의_이벤트_하나가_틀리면_그것만_격리하고_나머지는_행으로_바꾼다() {
        EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
        Envelope example = codec.read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));
        List<EnvelopeItem> items = new ArrayList<>(example.items());
        byte[] broken = "{\"event_id\":\"틀린 값\"}".getBytes(StandardCharsets.UTF_8);
        items.add(new EnvelopeItem(ItemType.EVENT, broken));
        QueueEntry entry = new QueueEntry(QueueStream.EVENTS, "1-0", 42, Instant.now(), codec.encode(new Envelope(example.header(), items)));

        ProcessedEntry processed = processor.process(entry);

        assertThat(processed.rows()).hasSize(2);
        assertThat(processed.quarantined()).hasSize(1);
        assertThat(processed.quarantined().getFirst().raw()).isEqualTo(broken);
        assertThat(processed.quarantined().getFirst().projectId()).isEqualTo(42);
    }

    @Test
    void 이슈_제목은_예외_타입과_메시지이고_길면_자른다() {
        ExceptionInfo exception = new ExceptionInfo("java.lang.IllegalStateException", "x", List.of());

        assertThat(EnvelopeProcessor.title(exception, "주문 실패")).isEqualTo("java.lang.IllegalStateException: 주문 실패");
        assertThat(EnvelopeProcessor.title(exception, "")).isEqualTo("java.lang.IllegalStateException");
        assertThat(EnvelopeProcessor.title(null, "메시지만")).isEqualTo("메시지만");
        assertThat(EnvelopeProcessor.title(null, "")).isEqualTo("(메시지 없음)");
        assertThat(EnvelopeProcessor.title(null, "가".repeat(500))).hasSize(EnvelopeProcessor.TITLE_MAX);
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
