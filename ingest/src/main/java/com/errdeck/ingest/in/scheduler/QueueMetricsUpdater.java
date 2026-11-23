package com.errdeck.ingest.in.scheduler;

import com.errdeck.ingest.app.QueueStats;
import com.errdeck.ingest.domain.Stream;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기열의 길이와 가장 오래 기다린 항목의 나이를 주기적으로 잰다(13.4).
 * 길이보다 나이가 더 중요한 신호다. 나이 30초는 화면에 보이는 것이 30초 늦다는 뜻이다.
 */
@Component
public class QueueMetricsUpdater {

    private static final Logger log = LoggerFactory.getLogger(QueueMetricsUpdater.class);

    private final QueueStats stats;
    private final Map<Stream, AtomicReference<QueueStats.Snapshot>> latest = new EnumMap<>(Stream.class);

    public QueueMetricsUpdater(QueueStats stats, MeterRegistry registry) {
        this.stats = stats;
        for (Stream stream : Stream.values()) {
            AtomicReference<QueueStats.Snapshot> ref = new AtomicReference<>();
            latest.put(stream, ref);
            Gauge.builder("errdeck.queue.length", ref, r -> r.get() == null ? Double.NaN : r.get().length())
                    .description("대기열에 쌓인 건수")
                    .tag("stream", stream.tag())
                    .register(registry);
            Gauge.builder("errdeck.queue.oldest.age", ref, r -> r.get() == null ? Double.NaN : r.get().oldestAge().toMillis() / 1000.0)
                    .description("대기열에서 가장 오래 기다린 항목의 나이")
                    .tag("stream", stream.tag())
                    .baseUnit("seconds")
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${errdeck.ingest.queue.metrics-interval}")
    public void update() {
        for (Stream stream : Stream.values()) {
            try {
                latest.get(stream).set(stats.snapshot(stream));
            } catch (RuntimeException e) {
                // 잴 수 없으면 값을 비워 둔다. 옛 값을 남기면 대시보드가 멀쩡해 보인다.
                latest.get(stream).set(null);
                log.debug("대기열 지표를 재지 못했다: {}", e.toString());
            }
        }
    }
}
