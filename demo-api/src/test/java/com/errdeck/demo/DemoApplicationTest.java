package com.errdeck.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Event;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 데모 앱의 예외 경로마다 SDK 가 보내는지, 400번대는 보내지 않는지 가짜 ingest 로 본다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "errdeck.flush-interval=100ms",
        "demo.scheduled-failure.interval=300ms",
})
class DemoApplicationTest {

    static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    static final HttpServer INGEST = startIngest();

    private static HttpServer startIngest() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
            server.createContext("/ingest/9001/envelope", exchange -> {
                codec.decode(exchange.getRequestBody().readAllBytes()).items().stream()
                        .filter(item -> item.type() == ItemType.EVENT)
                        .forEach(item -> EVENTS.add(Event.fromJson(item.payload())));
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void dsn(DynamicPropertyRegistry registry) {
        registry.add("errdeck.dsn", () -> "http://demoapikey@127.0.0.1:" + INGEST.getAddress().getPort() + "/9001");
    }

    @AfterAll
    static void stop() {
        INGEST.stop(0);
    }

    @LocalServerPort
    int port;

    @Test
    void 예외_경로마다_500번대만_보내고_400번대는_보내지_않는다() throws Exception {
        assertThat(get("/demo/handled/42")).isEqualTo(500);
        assertThat(get("/demo/unhandled")).isEqualTo(500);
        assertThat(get("/demo/async/42")).isEqualTo(202);
        assertThat(get("/demo/bad-request")).isEqualTo(400);
        assertThat(get("/demo/ok")).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(types()).contains(
                "com.errdeck.demo.DemoFailure",
                "java.lang.ArrayIndexOutOfBoundsException",
                "java.lang.IllegalStateException"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(messages()).contains("정산 배치가 실패했다"));
        assertThat(types()).doesNotContain("org.springframework.web.server.ResponseStatusException");

        Event handled = EVENTS.stream().filter(e -> e.primaryException().type().equals("com.errdeck.demo.DemoFailure")).findFirst().orElseThrow();
        assertThat(handled.exceptions()).hasSize(2);
        assertThat(handled.release()).isEqualTo("demo-api@0.1.0");
        assertThat(handled.exceptions().getFirst().frames()).anySatisfy(frame -> {
            assertThat(frame.module()).isEqualTo("com.errdeck.demo.OrderService");
            assertThat(frame.inApp()).isTrue();
        });
    }

    private static List<String> types() {
        return EVENTS.stream().map(e -> e.primaryException().type()).toList();
    }

    private static List<String> messages() {
        return EVENTS.stream().map(e -> e.primaryException().value()).toList();
    }

    private int get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
