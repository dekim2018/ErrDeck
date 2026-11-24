package com.errdeck.worker.app;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 13.4 의 worker 지표. 프로메테우스 이름은 errdeck_worker_stage_seconds, errdeck_clickhouse_batch_rows,
 * errdeck_clickhouse_flush_seconds, errdeck_e2e_latency_seconds 가 된다.
 */
@Component
public class WorkerMetrics {

    public enum Stage {
        DECOMPRESS, FINGERPRINT, WRITE
    }

    private final MeterRegistry registry;
    private final DistributionSummary batchRows;
    private final Timer flush;
    private final Timer e2e;

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.batchRows = DistributionSummary.builder("errdeck.clickhouse.batch")
                .description("ClickHouse 묶음 하나의 행 수")
                .baseUnit("rows")
                .tag("table", "events")
                .register(registry);
        this.flush = Timer.builder("errdeck.clickhouse.flush")
                .description("ClickHouse 묶음 쓰기 시간")
                .tag("table", "events")
                .register(registry);
        this.e2e = Timer.builder("errdeck.e2e.latency")
                .description("ingest 가 받은 때부터 저장하고 확인 처리할 때까지")
                .register(registry);
    }

    public void stage(Stage stage, long nanos) {
        Timer.builder("errdeck.worker.stage")
                .description("단계별 처리 시간")
                .tag("stage", stage.name().toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void flushed(int rows, long nanos) {
        batchRows.record(rows);
        flush.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void endToEnd(Duration latency) {
        e2e.record(latency.isNegative() ? Duration.ZERO : latency);
    }
}
