package com.errdeck.ingest.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 13.4 의 ingest 지표. 이름은 프로메테우스에서 errdeck_ingest_requests_total, errdeck_ingest_envelope_bytes 가 된다. */
@Component
public class IngestMetrics {

    public enum Result {
        ACCEPTED, TOO_LARGE, UNAUTHORIZED, FORBIDDEN, BAD_REQUEST, RATE_LIMITED, QUEUE_FULL, UNAVAILABLE;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Map<Result, Counter> requests = new EnumMap<>(Result.class);
    private final DistributionSummary envelopeBytes;

    public IngestMetrics(MeterRegistry registry) {
        for (Result result : Result.values()) {
            requests.put(result, Counter.builder("errdeck.ingest.requests")
                    .description("수집 요청의 결과")
                    .tag("result", result.tag())
                    .register(registry));
        }
        envelopeBytes = DistributionSummary.builder("errdeck.ingest.envelope")
                .description("받은 봉투의 압축된 크기")
                .baseUnit("bytes")
                .register(registry);
    }

    public void request(Result result) {
        requests.get(result).increment();
    }

    public void envelope(int bytes) {
        envelopeBytes.record(bytes);
    }
}
