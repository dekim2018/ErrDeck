package com.errdeck.worker.app;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeException;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.fingerprint.Fingerprint;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.worker.app.WorkerMetrics.Stage;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.QueueEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 대기열 항목 하나를 저장할 행으로 바꾼다. 압축 해제, 해석, 지문 계산만 하고 I/O 는 하지 않는다(14.5).
 * 계산 풀의 스레드가 I/O 를 기다리면 DB가 느려질 때 계산 전체가 멈추기 때문이다.
 */
public class EnvelopeProcessor {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeProcessor.class);

    private final EnvelopeCodec codec;
    private final Fingerprinter fingerprinter;
    private final WorkerMetrics metrics;
    private final LongAdder unreadable = new LongAdder();

    public EnvelopeProcessor(EnvelopeCodec codec, Fingerprinter fingerprinter, WorkerMetrics metrics) {
        this.codec = codec;
        this.fingerprinter = fingerprinter;
        this.metrics = metrics;
    }

    public ProcessedEntry process(QueueEntry entry) {
        long start = System.nanoTime();
        Envelope envelope;
        try {
            envelope = codec.decode(entry.body());
        } catch (EnvelopeException e) {
            // 격리 테이블로 옮기는 것은 08 작업에서 한다. 그때까지는 버리고 세어 둔다.
            unreadable.increment();
            log.warn("봉투를 읽지 못해 버린다. 프로젝트 {}, 항목 {}: {}", entry.projectId(), entry.id(), e.getMessage());
            return new ProcessedEntry(entry.ref(), entry.receivedAt(), List.of());
        }
        metrics.stage(Stage.DECOMPRESS, System.nanoTime() - start);

        start = System.nanoTime();
        List<EventRow> rows = new ArrayList<>();
        for (EnvelopeItem item : envelope.items()) {
            // 트랜잭션은 19 작업, client_report 는 08 작업에서 저장한다.
            if (item.type() != ItemType.EVENT) {
                continue;
            }
            try {
                rows.add(toRow(entry, Event.fromJson(item.payload())));
            } catch (IllegalArgumentException e) {
                unreadable.increment();
                log.warn("이벤트를 읽지 못해 버린다. 프로젝트 {}, 항목 {}: {}", entry.projectId(), entry.id(), e.getMessage());
            }
        }
        metrics.stage(Stage.FINGERPRINT, System.nanoTime() - start);
        return new ProcessedEntry(entry.ref(), entry.receivedAt(), rows);
    }

    /** 해석하지 못해 버린 봉투와 이벤트의 수. */
    public long unreadable() {
        return unreadable.sum();
    }

    private EventRow toRow(QueueEntry entry, Event event) {
        Fingerprint fingerprint = fingerprinter.fingerprint(event);
        ExceptionInfo primary = event.primaryException();
        String message = event.message() != null ? event.message() : primary != null && primary.value() != null ? primary.value() : "";
        return new EventRow(
                entry.projectId(),
                event.eventId(),
                fingerprint.value(),
                event.timestamp(),
                entry.receivedAt(),
                nullToEmpty(event.environment()),
                nullToEmpty(event.release()),
                event.level(),
                primary == null ? "" : primary.type(),
                message,
                userHash(event.userId()),
                nullToEmpty(event.traceId()),
                event.tags(),
                false,
                new String(event.toJson(), StandardCharsets.UTF_8));
    }

    /**
     * 영향 사용자 수는 같은 사람인지만 알면 되므로 해시만 저장하고 원래 값은 원본 안에만 둔다(10.2).
     * 사용자가 없으면 0이다.
     */
    static long userHash(String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xff);
            }
            // ClickHouse 의 UInt64 열에 JSON 숫자로 넣으므로 음수가 되지 않게 맨 위 비트를 버린다. 63비트로도 충돌은 걱정할 수준이 아니다.
            hash &= Long.MAX_VALUE;
            return hash == 0 ? 1 : hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
