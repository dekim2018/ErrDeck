package com.errdeck.ingest.app;

import com.errdeck.ingest.config.IngestProperties;
import com.errdeck.ingest.domain.Admission;
import com.errdeck.ingest.domain.ProjectKey;
import com.errdeck.ingest.domain.Stream;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 봉투를 대기열에 넣는다. 진행 중인 Redis 호출을 세마포어로 제한하고, 자리가 없으면 기다리지 않고 거절한다(14.4).
 * 기다리게 하면 요청이 쌓이고, 쌓인 요청은 곧 메모리다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EnvelopeQueue queue;
    private final Semaphore inFlight;

    public IngestService(EnvelopeQueue queue, IngestProperties properties) {
        this.queue = queue;
        this.inFlight = new Semaphore(properties.redis().maxInFlight());
    }

    public Admission ingest(ProjectKey key, Stream stream, byte[] body) {
        if (!inFlight.tryAcquire()) {
            return new Admission.Unavailable();
        }
        try {
            return queue.admit(key, stream, body);
        } catch (RuntimeException e) {
            // 장애 중에는 요청마다 실패하므로 한 줄로만 남긴다. 건수는 지표로 본다.
            log.warn("대기열에 넣지 못했다: {}", e.toString());
            return new Admission.Unavailable();
        } finally {
            inFlight.release();
        }
    }
}
