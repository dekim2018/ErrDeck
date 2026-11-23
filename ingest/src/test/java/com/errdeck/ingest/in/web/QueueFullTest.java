package com.errdeck.ingest.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.core.envelope.Gzip;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import com.errdeck.ingest.support.Projects;
import com.errdeck.ingest.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "errdeck.ingest.queue.events-max-length=3",
        "errdeck.ingest.queue.full-retry-after=7s",
})
class QueueFullTest extends TestInfra {

    private static final byte[] ENVELOPE = Gzip.compress(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void emptyQueues() {
        redis.delete(java.util.List.of("errdeck:stream:events", "errdeck:stream:tx"));
    }

    @Test
    void 대기열이_상한에_닿으면_한도가_남아도_503과_Retry_After를_주고_토큰은_쓰지_않는다() throws Exception {
        // 분당 1건으로 두어 테스트 동안 다시 채워지는 토큰이 남은 토큰 수를 흐리지 않게 한다.
        Projects.Created project = new Projects(jdbc).spring(1);

        for (int i = 0; i < 3; i++) {
            assertThat(send(project, "").statusCode()).isEqualTo(202);
        }
        HttpResponse<String> full = send(project, "");

        assertThat(full.statusCode()).isEqualTo(503);
        assertThat(full.headers().firstValue("Retry-After")).hasValue("7");
        assertThat(redis.opsForStream().size("errdeck:stream:events")).isEqualTo(3);
        // 버킷 1000에서 받은 3건만 빠졌다. 거절된 요청은 토큰을 쓰지 않았다.
        assertThat(Double.parseDouble((String) redis.opsForHash().get("errdeck:rl:" + project.projectId(), "tokens")))
                .isGreaterThan(996.9).isLessThan(997.1);
    }

    @Test
    void 에러_대기열이_차도_트랜잭션_대기열은_따로_받는다() throws Exception {
        Projects.Created project = new Projects(jdbc).spring(6000);
        for (int i = 0; i < 3; i++) {
            send(project, "");
        }

        assertThat(send(project, "").statusCode()).isEqualTo(503);
        assertThat(send(project, "?kind=tx").statusCode()).isEqualTo(202);
    }

    private HttpResponse<String> send(Projects.Created project, String query) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(
                                    "http://localhost:" + port + "/ingest/" + project.projectId() + "/envelope" + query))
                            .header("X-ErrDeck-Key", project.publicKey())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(ENVELOPE))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
