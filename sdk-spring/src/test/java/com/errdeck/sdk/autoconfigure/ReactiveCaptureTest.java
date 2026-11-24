package com.errdeck.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.sdk.support.FakeIngest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** WebFlux 앱(팬파라이브 gateway)에 붙였을 때 처리되지 않은 서버 오류만 보내는지 본다. */
@SpringBootTest(classes = ReactiveCaptureTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.web-application-type=reactive", "errdeck.flush-interval=100ms"})
class ReactiveCaptureTest {

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

    @Test
    void 서버_오류는_보내고_400번대_오류는_보내지_않는다() throws Exception {
        assertThat(get("/bad")).isEqualTo(400);
        assertThat(get("/boom")).isEqualTo(500);

        await().atMost(Duration.ofSeconds(5)).until(() -> !INGEST.events().isEmpty());
        Thread.sleep(500);
        assertThat(INGEST.events()).extracting(e -> e.primaryException().type()).containsExactly("java.lang.IllegalStateException");
        assertThat(INGEST.events().getFirst().request().url()).endsWith("/boom");
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
    }

    @RestController
    static class Endpoints {

        @GetMapping("/boom")
        Mono<String> boom() {
            return Mono.error(new IllegalStateException("뒤쪽 서비스 호출 실패"));
        }

        @GetMapping("/bad")
        Mono<String> bad() {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 요청"));
        }
    }
}
