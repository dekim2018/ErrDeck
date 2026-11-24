package com.errdeck.api.out.prometheus;

import com.errdeck.api.app.PerformanceService.Endpoint;
import com.errdeck.api.app.PerformanceService.EndpointMetrics;
import com.errdeck.core.json.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 감시 대상의 요청 시간 히스토그램(http_server_requests_seconds)으로 엔드포인트 표를 만든다(8.1). 프로메테우스의 job 라벨은
 * 프로젝트 슬러그와 같게 둔다. 프로메테우스가 2초 안에 답하지 않으면 null 을 돌려 화면이 표본 표로 바꾼다(14.9).
 */
public class PrometheusEndpointMetrics implements EndpointMetrics {

    private static final Logger log = LoggerFactory.getLogger(PrometheusEndpointMetrics.class);
    private static final Pattern JOB = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private final URI base;
    private final Duration timeout;
    private final HttpClient client;

    public PrometheusEndpointMetrics(URI base, Duration timeout) {
        this.base = base;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<Endpoint> endpoints(String job, Duration window) {
        if (!JOB.matcher(job).matches()) {
            return null;
        }
        String selector = "job=\"" + job + "\", uri!~\"/actuator.*\"";
        String w = "[" + window.toSeconds() + "s]";
        try {
            Map<String, Double> counts = vector("sum by (method, uri) (increase(http_server_requests_seconds_count{" + selector + "}" + w + "))");
            Map<String, Double> errors = vector("sum by (method, uri) (increase(http_server_requests_seconds_count{" + selector
                    + ", status=~\"5..\"}" + w + "))");
            Map<String, Double> p95 = vector("histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{"
                    + selector + "}" + w + ")))");
            List<Endpoint> out = new ArrayList<>();
            counts.forEach((name, count) -> {
                if (count >= 0.5) {
                    double p = p95.getOrDefault(name, Double.NaN);
                    out.add(new Endpoint(name, Math.round(count), Double.isNaN(p) ? 0 : p * 1000, errors.getOrDefault(name, 0.0) / count));
                }
            });
            out.sort((a, b) -> Long.compare(b.requests(), a.requests()));
            return out;
        } catch (Exception e) {
            log.debug("프로메테우스를 읽지 못했다: {}", e.toString());
            return null;
        }
    }

    /** job 라벨별 즉시 값. 요약 카드(12.2)가 쓴다. */
    public Map<String, Double> byJob(String promql) throws Exception {
        return vector(promql, "job");
    }

    /** 즉시 질의의 결과를 "메서드 경로" → 값으로. */
    private Map<String, Double> vector(String promql) throws Exception {
        return vector(promql, null);
    }

    private Map<String, Double> vector(String promql, String label) throws Exception {
        URI uri = base.resolve("/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("프로메테우스가 " + response.statusCode() + " 로 답했다");
        }
        Map<String, Object> body = Json.parseObject(response.body());
        Map<String, Double> out = new HashMap<>();
        if (body.get("data") instanceof Map<?, ?> data && data.get("result") instanceof List<?> result) {
            for (Object item : result) {
                if (item instanceof Map<?, ?> row && row.get("metric") instanceof Map<?, ?> metric && row.get("value") instanceof List<?> value) {
                    String key = label != null ? String.valueOf(metric.get(label)) : metric.get("method") + " " + metric.get("uri");
                    out.put(key, Double.parseDouble(String.valueOf(value.get(1))));
                }
            }
        }
        return out;
    }
}
