package com.errdeck.worker.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 13.4 의 worker 지표. 프로메테우스 이름은 errdeck_worker_stage_seconds, errdeck_clickhouse_batch_rows,
 * errdeck_clickhouse_flush_seconds, errdeck_e2e_latency_seconds, errdeck_events_processed_total 이 된다.
 */
@Component
public class WorkerMetrics {

    public enum Stage {
        DECOMPRESS, RESTORE, FINGERPRINT, WRITE
    }

    /** 받은 이벤트가 어떻게 끝났는지. 넷을 더하면 받은 이벤트 수다. */
    public enum Outcome {
        /** 원본까지 저장했다. */
        STORED,
        /** 이슈별 분당 상한을 넘어 건수만 남겼다. */
        COUNTED_ONLY,
        /** 이미 처리한 event_id 라 버렸다. */
        DUPLICATE,
        /** 처리할 수 없어 격리했다. */
        QUARANTINED
    }

    private final MeterRegistry registry;
    private final Timer e2e;

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.e2e = Timer.builder("errdeck.e2e.latency")
                .description("ingest 가 받은 때부터 저장하고 확인 처리할 때까지")
                .register(registry);
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    public void stage(Stage stage, long nanos) {
        Timer.builder("errdeck.worker.stage")
                .description("단계별 처리 시간")
                .tag("stage", stage.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void flushed(String table, int rows, long nanos) {
        DistributionSummary.builder("errdeck.clickhouse.batch")
                .description("ClickHouse 묶음 하나의 행 수")
                .baseUnit("rows")
                .tag("table", table)
                .register(registry)
                .record(rows);
        Timer.builder("errdeck.clickhouse.flush")
                .description("ClickHouse 묶음 쓰기 시간")
                .tag("table", table)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void processed(Outcome outcome, long count) {
        if (count > 0) {
            counter(outcome).increment(count);
        }
    }

    public void endToEnd(Duration latency) {
        e2e.record(latency.isNegative() ? Duration.ZERO : latency);
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder("errdeck.events.processed")
                .description("처리한 이벤트의 결과")
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }
}
