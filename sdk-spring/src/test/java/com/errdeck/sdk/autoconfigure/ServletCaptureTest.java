package com.errdeck.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.event.Event;
import com.errdeck.sdk.ErrDeck;
import com.errdeck.sdk.support.FakeIngest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 서블릿 앱에 붙였을 때 최종 상태 코드가 500 이상인 예외만 보내는지 본다(11.2). */
@SpringBootTest(classes = ServletCaptureTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"errdeck.flush-interval=100ms", "errdeck.environment=test", "errdeck.in-app-packages=com.errdeck.sdk"})
class ServletCaptureTest {

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
    void 전역_처리기가_500으로_바꾼_예외를_보낸다() throws Exception {
        assertThat(get("/handled-500")).isEqualTo(500);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(types()).containsExactly("java.lang.IllegalStateException"));
        Event event = INGEST.events().getFirst();
        assertThat(event.request().url()).endsWith("/handled-500");
        assertThat(event.request().method()).isEqualTo("GET");
    }

    @Test
    void 처리되지_않은_예외를_한_번만_보낸다() throws Exception {
        assertThat(get("/unhandled")).isEqualTo(500);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(types()).contains("java.lang.UnsupportedOperationException"));
        Thread.sleep(500);
        assertThat(types()).containsExactly("java.lang.UnsupportedOperationException");
    }

    @Test
    void 상태_400번대로_끝난_예외는_보내지_않는다() throws Exception {
        assertThat(get("/handled-400")).isEqualTo(400);

        Thread.sleep(800);
        assertThat(INGEST.events()).isEmpty();
    }

    @Test
    void 로그에_예외와_함께_남긴_에러를_보낸다() throws Exception {
        assertThat(get("/log")).isEqualTo(200);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(types()).containsExactly("java.io.UncheckedIOException"));
    }

    @Test
    void 코드에서_직접_보낸_예외에_요청_정보를_붙인다() throws Exception {
        assertThat(get("/capture")).isEqualTo(200);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(types()).containsExactly("java.lang.ArithmeticException"));
        assertThat(INGEST.events().getFirst().request().url()).endsWith("/capture");
    }

    private List<String> types() {
        return INGEST.events().stream().map(e -> e.primaryException().type()).toList();
    }

    private int get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @EnableAutoConfiguration
    @Import({Endpoints.class, Advice.class})
    static class App {
    }

    @RestController
    static class Endpoints {

        private static final Logger log = LoggerFactory.getLogger(Endpoints.class);

        @GetMapping("/handled-500")
        String handled500() {
            throw new IllegalStateException("DB 연결 실패");
        }

        @GetMapping("/handled-400")
        String handled400() {
            throw new IllegalArgumentException("잘못된 입력");
        }

        @GetMapping("/unhandled")
        String unhandled() {
            throw new UnsupportedOperationException("처리기 없음");
        }

        @GetMapping("/log")
        String log() {
            log.error("소비자에서 처리 실패", new java.io.UncheckedIOException(new java.io.IOException("끊김")));
            return "ok";
        }

        @GetMapping("/capture")
        String capture() {
            try {
                int zero = 0;
                return Integer.toString(1 / zero);
            } catch (ArithmeticException e) {
                ErrDeck.capture(e);
                return "ok";
            }
        }
    }

    @RestControllerAdvice
    static class Advice {

        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<String> serverError(IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("서버 오류");
        }

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<String> badRequest(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("잘못된 요청");
        }
    }
}
