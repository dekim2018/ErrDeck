package com.errdeck.worker.uptime;

import com.errdeck.core.net.UrlGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * HTTP 호출 한 번으로 모니터를 점검한다(9.1). 리다이렉트는 따라가지 않고(9.4), 주소는 부르기 직전에 다시 확인한다.
 * HTTPS 면 받은 인증서의 만료일을 함께 읽는다(9.3).
 */
public class UptimeChecker {

    private static final int MAX_BODY = 64 * 1024;

    private final HttpClient client;
    private final UrlGuard guard;
    private final Clock clock;

    public UptimeChecker(UrlGuard guard, Clock clock) {
        this.guard = guard;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public CheckResult check(Monitor monitor) {
        Instant start = clock.instant();
        long begin = System.nanoTime();
        try {
            URI uri = guard.check(monitor.url());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(monitor.timeoutMs()))
                    .method(monitor.method(), HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "ErrDeck-Uptime/0.1")
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long duration = (System.nanoTime() - begin) / 1_000_000;
            Instant certExpires = response.sslSession()
                    .flatMap(s -> {
                        try {
                            return java.util.Optional.of(((X509Certificate) s.getPeerCertificates()[0]).getNotAfter().toInstant());
                        } catch (Exception e) {
                            return java.util.Optional.empty();
                        }
                    })
                    .orElse(null);
            int status = response.statusCode();
            String error = null;
            if (status < monitor.expectedStatusMin() || status > monitor.expectedStatusMax()) {
                error = "상태 코드 " + status;
            } else if (monitor.expectedBody() != null && !monitor.expectedBody().isBlank()) {
                byte[] body = response.body();
                String text = new String(body, 0, Math.min(body.length, MAX_BODY), StandardCharsets.UTF_8);
                if (!text.contains(monitor.expectedBody())) {
                    error = "본문에 \"" + monitor.expectedBody() + "\" 가 없다";
                }
            }
            return new CheckResult(monitor.id(), monitor.projectId(), start, error == null, status, duration, error, certExpires);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(monitor, start, begin, "중단되었다");
        } catch (java.net.http.HttpTimeoutException e) {
            return failed(monitor, start, begin, "제한 시간 " + monitor.timeoutMs() + "ms 초과");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return failed(monitor, start, begin, message);
        }
    }

    private static CheckResult failed(Monitor monitor, Instant start, long begin, String error) {
        return new CheckResult(monitor.id(), monitor.projectId(), start, false, 0, (System.nanoTime() - begin) / 1_000_000, error, null);
    }
}
