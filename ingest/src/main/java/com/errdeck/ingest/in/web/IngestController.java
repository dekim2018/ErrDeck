package com.errdeck.ingest.in.web;

import com.errdeck.ingest.app.IngestMetrics;
import com.errdeck.ingest.app.IngestMetrics.Result;
import com.errdeck.ingest.app.IngestService;
import com.errdeck.ingest.config.IngestProperties;
import com.errdeck.ingest.domain.Admission;
import com.errdeck.ingest.domain.ProjectKey;
import com.errdeck.ingest.domain.Stream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 봉투를 받아 대기열에 넣는다. 본문은 풀지도 해석하지도 않는다(adr/0003). 그 일은 worker 의 몫이다.
 * 키와 출처는 {@link IngestAuthFilter} 가 이미 확인했다.
 */
@RestController
public class IngestController {

    private final IngestService service;
    private final IngestProperties properties;
    private final IngestMetrics metrics;

    public IngestController(IngestService service, IngestProperties properties, IngestMetrics metrics) {
        this.service = service;
        this.properties = properties;
        this.metrics = metrics;
    }

    @PostMapping("/ingest/{projectId}/envelope")
    public void envelope(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProjectKey key = (ProjectKey) request.getAttribute(IngestAuthFilter.PROJECT_KEY);
        Stream stream = Stream.fromKind(QueryString.get(request, "kind"));
        if (key == null || stream == null) {
            metrics.request(Result.BAD_REQUEST);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // Content-Length 가 없는 요청(청크 전송)도 있으므로 상한보다 1바이트 더 읽어 본다.
        long max = properties.maxEnvelopeSize().toBytes();
        byte[] body = request.getInputStream().readNBytes((int) max + 1);
        if (body.length > max) {
            metrics.request(Result.TOO_LARGE);
            Responses.reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, null);
            return;
        }
        if (body.length == 0) {
            metrics.request(Result.BAD_REQUEST);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        metrics.envelope(body.length);

        switch (service.ingest(key, stream, body)) {
            case Admission.Accepted() -> {
                metrics.request(Result.ACCEPTED);
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
            }
            case Admission.RateLimited(var retryAfter) -> {
                metrics.request(Result.RATE_LIMITED);
                Responses.reject(response, 429, retryAfter);
            }
            case Admission.QueueFull() -> {
                metrics.request(Result.QUEUE_FULL);
                Responses.reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, properties.queue().fullRetryAfter());
            }
            case Admission.Unavailable() -> {
                metrics.request(Result.UNAVAILABLE);
                Responses.reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, properties.queue().fullRetryAfter());
            }
        }
    }
}
