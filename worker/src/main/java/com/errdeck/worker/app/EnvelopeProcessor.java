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
import com.errdeck.worker.domain.Quarantine;
import com.errdeck.worker.domain.QueueEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 대기열 항목 하나를 저장할 행으로 바꾼다. 압축 해제, 해석, 지문 계산만 하고 I/O 는 하지 않는다(14.5).
 * 계산 풀의 스레드가 I/O 를 기다리면 DB가 느려질 때 계산 전체가 멈추기 때문이다.
 */
public class EnvelopeProcessor {

    static final int TITLE_MAX = 200;

    private final EnvelopeCodec codec;
    private final Fingerprinter fingerprinter;
    private final WorkerMetrics metrics;

    public EnvelopeProcessor(EnvelopeCodec codec, Fingerprinter fingerprinter, WorkerMetrics metrics) {
        this.codec = codec;
        this.fingerprinter = fingerprinter;
        this.metrics = metrics;
    }

    /**
     * @throws EnvelopeException 봉투를 풀거나 나누지 못했을 때. 단계 구조가 다시 시도하고, 세 번 실패하면 격리한다
     */
    public ProcessedEntry process(QueueEntry entry) {
        long start = System.nanoTime();
        Envelope envelope = codec.decode(entry.body());
        metrics.stage(Stage.DECOMPRESS, System.nanoTime() - start);

        start = System.nanoTime();
        List<EventRow> rows = new ArrayList<>();
        List<Quarantine> quarantined = new ArrayList<>();
        for (EnvelopeItem item : envelope.items()) {
            // 트랜잭션은 19 작업에서 저장한다. client_report 는 아직 저장할 곳이 없다(6장 끝).
            if (item.type() != ItemType.EVENT) {
                continue;
            }
            try {
                rows.add(toRow(entry, Event.fromJson(item.payload())));
            } catch (IllegalArgumentException e) {
                // 이벤트 하나가 틀렸다고 봉투의 나머지까지 버리지 않는다. 다시 해도 같으므로 바로 격리한다.
                quarantined.add(new Quarantine(entry.projectId(), item.payload(), "이벤트 형식: " + e.getMessage()));
            }
        }
        metrics.stage(Stage.FINGERPRINT, System.nanoTime() - start);
        return new ProcessedEntry(entry.ref(), entry.receivedAt(), rows, quarantined);
    }

    private EventRow toRow(QueueEntry entry, Event event) {
        Fingerprint fingerprint = fingerprinter.fingerprint(event);
        ExceptionInfo primary = event.primaryException();
        String message = event.message() != null ? event.message() : primary != null && primary.value() != null ? primary.value() : "";
        return new EventRow(
                entry.projectId(),
                event.eventId(),
                fingerprint.value(),
                fingerprint.ruleVersion(),
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
                new String(event.toJson(), StandardCharsets.UTF_8),
                event.count(),
                title(primary, message),
                fingerprinter.culprit(event));
    }

    /** 이슈 목록에 보일 제목. 목록 한 줄에 들어가도록 자른다. */
    static String title(ExceptionInfo primary, String message) {
        String title = primary == null ? message : message.isEmpty() ? primary.type() : primary.type() + ": " + message;
        title = title.isEmpty() ? "(메시지 없음)" : title.strip();
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX - 1) + "…";
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
