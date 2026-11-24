package com.errdeck.sdk.transport;

import com.errdeck.core.dsn.Dsn;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JDK HttpClient 로 보낸다. 제한 시간은 3초다(14.9). 키는 헤더로 보낸다(5.2). */
public final class HttpTransport implements Transport {

    private final HttpClient client;
    private final URI uri;
    private final String key;
    private final Duration timeout;

    public HttpTransport(Dsn dsn, Duration timeout) {
        this(dsn, timeout, false);
    }

    /** @param transactions true 면 트랜잭션 대기열로 보낸다({@code ?kind=tx}, 5.4). 에러가 트랜잭션에 밀리지 않게 나눈다 */
    public HttpTransport(Dsn dsn, Duration timeout, boolean transactions) {
        this.uri = transactions ? java.net.URI.create(dsn.envelopeUri() + "?kind=tx") : dsn.envelopeUri();
        this.key = dsn.publicKey();
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public Response send(byte[] envelope) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("X-ErrDeck-Key", key)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(envelope))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        Long retryAfter = response.headers().firstValue("Retry-After").flatMap(HttpTransport::seconds).orElse(null);
        return new Response(response.statusCode(), retryAfter);
    }

    private static java.util.Optional<Long> seconds(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
