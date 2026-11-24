package com.errdeck.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.event.Event;
import com.errdeck.core.event.Transaction;
import com.errdeck.sdk.support.FakeIngest;
import com.errdeck.sdk.tracing.Tracing;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** 들어오는 요청, 나가는 호출, 사용자 정의 구간을 기록하고 서비스 사이를 trace_id 로 잇는지 본다(8.2, 8.3). */
@SpringBootTest(classes = ServletTracingTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"errdeck.flush-interval=100ms", "errdeck.tracing.sample-rate=1.0"})
class ServletTracingTest {

    static final FakeIngest INGEST = new FakeIngest();

    @DynamicPropertySource
    static void dsn(DynamicPropertyRegistry registry) {
        registry.add("errdeck.dsn", INGEST::dsn);
    }

    @AfterAll
    static void stop() {
        INGEST.close();
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void clear() {
        INGEST.envelopes.clear();
    }

    @Test
    void 다른_서비스를_부른_요청은_같은_trace_id_로_이어진다() throws Exception {
        assertThat(get("/front")).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(INGEST.transactions()).hasSize(2));
        List<Transaction> txs = INGEST.transactions();
        Transaction front = txs.stream().filter(t -> t.name().equals("GET /front")).findFirst().orElseThrow();
        Transaction back = txs.stream().filter(t -> t.name().equals("GET /back/{id}")).findFirst().orElseThrow();
        assertThat(back.traceId()).isEqualTo(front.traceId());
        Transaction.Span call = front.spans().stream().filter(s -> s.op().equals("http.client")).findFirst().orElseThrow();
        assertThat(back.parentSpanId()).isEqualTo(call.spanId());
        assertThat(call.description()).isEqualTo("GET 127.0.0.1/back/7");
        assertThat(front.spans()).anyMatch(s -> s.op().equals("custom") && s.description().equals("계산"));
        assertThat(front.partial()).isFalse();
    }

    @Test
    void 에러_이벤트에_진행_중인_trace_id_를_넣는다() throws Exception {
        assertThat(get("/boom")).isEqualTo(500);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(INGEST.events()).hasSize(1);
            assertThat(INGEST.transactions()).hasSize(1);
        });
        Event event = INGEST.events().getFirst();
        assertThat(event.traceId()).isEqualTo(INGEST.transactions().getFirst().traceId());
        assertThat(INGEST.transactions().getFirst().status()).isEqualTo("500");
    }

    private int get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @EnableAutoConfiguration
    @Import(Endpoints.class)
    static class App {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    @RestController
    static class Endpoints {

        private final RestClient client;

        Endpoints(RestClient.Builder builder) {
            this.client = builder.build();
        }

        @GetMapping("/front")
        String front(@Value("${local.server.port}") int port) {
            Tracing.span("custom", "계산", () -> 1 + 1);
            return client.get().uri("http://127.0.0.1:" + port + "/back/7").retrieve().body(String.class);
        }

        @GetMapping("/back/{id}")
        String back(@PathVariable("id") String id) {
            return "back " + id;
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("터졌다");
        }
    }
}
