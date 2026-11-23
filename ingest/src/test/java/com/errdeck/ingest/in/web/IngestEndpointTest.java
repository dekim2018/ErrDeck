package com.errdeck.ingest.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.core.envelope.Gzip;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import com.errdeck.ingest.support.Projects;
import com.errdeck.ingest.support.TestInfra;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "errdeck.ingest.rate-limit.burst=20",
        "errdeck.ingest.queue.metrics-interval=200ms",
})
class IngestEndpointTest extends TestInfra {

    private static final byte[] ENVELOPE = Gzip.compress(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RedisConnectionFactory connectionFactory;

    private Projects projects;
    private RedisTemplate<String, byte[]> redis;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        projects = new Projects(jdbc);
        redis = new RedisTemplate<>();
        redis.setConnectionFactory(connectionFactory);
        redis.setKeySerializer(RedisSerializer.string());
        redis.setHashKeySerializer(RedisSerializer.string());
        redis.setHashValueSerializer(RedisSerializer.byteArray());
        redis.afterPropertiesSet();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void 서버_SDK가_보낸_봉투가_에러_대기열에_압축된_그대로_들어간다() throws Exception {
        Projects.Created project = projects.spring(6000);

        HttpResponse<String> response = send(post(project.projectId(), "").header("X-ErrDeck-Key", project.publicKey()));

        assertThat(response.statusCode()).isEqualTo(202);
        List<MapRecord<String, String, byte[]>> entries = entriesOf("errdeck:stream:events", project.projectId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getValue().get("body")).isEqualTo(ENVELOPE);
        assertThat(Long.parseLong(new String(entries.getFirst().getValue().get("received_at"))))
                .isCloseTo(System.currentTimeMillis(), org.assertj.core.data.Offset.offset(10_000L));
    }

    @Test
    void kind_tx_로_보낸_봉투는_트랜잭션_대기열에_들어간다() throws Exception {
        Projects.Created project = projects.spring(6000);

        HttpResponse<String> response = send(post(project.projectId(), "?kind=tx").header("X-ErrDeck-Key", project.publicKey()));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(entriesOf("errdeck:stream:tx", project.projectId())).hasSize(1);
        assertThat(entriesOf("errdeck:stream:events", project.projectId())).isEmpty();
    }

    @Test
    void 모르는_kind_는_400이다() throws Exception {
        Projects.Created project = projects.spring(6000);

        assertThat(send(post(project.projectId(), "?kind=log").header("X-ErrDeck-Key", project.publicKey())).statusCode())
                .isEqualTo(400);
    }

    @Test
    void 키가_없거나_틀리거나_다른_프로젝트의_키면_401이다() throws Exception {
        Projects.Created project = projects.spring(6000);
        Projects.Created other = projects.spring(6000);

        assertThat(send(post(project.projectId(), "")).statusCode()).isEqualTo(401);
        assertThat(send(post(project.projectId(), "").header("X-ErrDeck-Key", "unknownkey")).statusCode()).isEqualTo(401);
        assertThat(send(post(project.projectId(), "").header("X-ErrDeck-Key", "bad key!")).statusCode()).isEqualTo(401);
        assertThat(send(post(project.projectId(), "").header("X-ErrDeck-Key", other.publicKey())).statusCode()).isEqualTo(401);
    }

    @Test
    void 폐기된_키는_401이고_폐기_시각이_미래면_아직_받는다() throws Exception {
        Projects.Created revoked = projects.create("spring", 6000, List.of(), Instant.now().minusSeconds(60));
        Projects.Created rotating = projects.create("spring", 6000, List.of(), Instant.now().plusSeconds(3600));

        assertThat(send(post(revoked.projectId(), "").header("X-ErrDeck-Key", revoked.publicKey())).statusCode()).isEqualTo(401);
        assertThat(send(post(rotating.projectId(), "").header("X-ErrDeck-Key", rotating.publicKey())).statusCode()).isEqualTo(202);
    }

    @Test
    void 브라우저_키는_허용한_출처에서만_받고_응답에_출처_헤더를_붙인다() throws Exception {
        Projects.Created project = projects.vue(List.of("https://copage.example.com"));
        String query = "?key=" + project.publicKey();

        HttpResponse<String> allowed = send(post(project.projectId(), query)
                .header("Origin", "https://copage.example.com").header("Content-Type", "text/plain"));
        HttpResponse<String> otherOrigin = send(post(project.projectId(), query).header("Origin", "https://evil.example.com"));
        HttpResponse<String> noOrigin = send(post(project.projectId(), query));

        assertThat(allowed.statusCode()).isEqualTo(202);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://copage.example.com");
        assertThat(otherOrigin.statusCode()).isEqualTo(403);
        assertThat(noOrigin.statusCode()).isEqualTo(403);
    }

    @Test
    void 봉투가_200KB를_넘으면_413이다() throws Exception {
        Projects.Created project = projects.spring(6000);
        byte[] tooLarge = new byte[200 * 1024 + 1];

        HttpResponse<String> withLength = send(request(project.projectId(), "")
                .header("X-ErrDeck-Key", project.publicKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(tooLarge)));
        // 길이를 미리 알리지 않는 청크 전송도 읽으면서 상한을 확인한다.
        HttpResponse<String> chunked = send(request(project.projectId(), "")
                .header("X-ErrDeck-Key", project.publicKey())
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(tooLarge))));

        assertThat(withLength.statusCode()).isEqualTo(413);
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(entriesOf("errdeck:stream:events", project.projectId())).isEmpty();
    }

    @Test
    void 빈_본문은_400이다() throws Exception {
        Projects.Created project = projects.spring(6000);

        HttpResponse<String> response = send(request(project.projectId(), "")
                .header("X-ErrDeck-Key", project.publicKey())
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void 순간_허용량을_다_쓰면_429와_다음_토큰까지의_Retry_After를_준다() throws Exception {
        Projects.Created project = projects.spring(1);

        for (int i = 0; i < 20; i++) {
            assertThat(send(post(project.projectId(), "").header("X-ErrDeck-Key", project.publicKey())).statusCode()).isEqualTo(202);
        }
        HttpResponse<String> limited = send(post(project.projectId(), "").header("X-ErrDeck-Key", project.publicKey()));

        assertThat(limited.statusCode()).isEqualTo(429);
        // 분당 1건이므로 다음 토큰은 60초 안에 생긴다.
        assertThat(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 60);
    }

    @Test
    void 동시에_보낸_요청_중_한도를_넘겨_들어간_건이_없다() throws Exception {
        Projects.Created project = projects.spring(1);
        int requests = 200;

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                tasks.add(() -> send(post(project.projectId(), "").header("X-ErrDeck-Key", project.publicKey())).statusCode());
            }
            for (Future<Integer> future : executor.invokeAll(tasks)) {
                statuses.add(future.get());
            }
        }

        // 분당 1건이라 테스트 동안 다시 채워지는 토큰은 1건 미만이다. 받은 건수는 버킷 크기 20과 같아야 한다.
        assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(20);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(requests - 20);
        assertThat(entriesOf("errdeck:stream:events", project.projectId())).hasSize(20);
    }

    @Test
    void 프로메테우스_엔드포인트에_수집_지표와_대기열_지표가_나온다() throws Exception {
        Projects.Created project = projects.spring(6000);
        send(post(project.projectId(), "").header("X-ErrDeck-Key", project.publicKey()));
        Thread.sleep(500);

        String body = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertThat(body).contains("errdeck_ingest_requests_total{result=\"accepted\"}");
        assertThat(body).contains("errdeck_ingest_envelope_bytes_count");
        assertThat(body).contains("errdeck_queue_length{stream=\"events\"}");
        assertThat(body).contains("errdeck_queue_oldest_age_seconds{stream=\"tx\"}");
    }

    private List<MapRecord<String, String, byte[]>> entriesOf(String stream, long projectId) {
        List<MapRecord<String, String, byte[]>> all = redis.<String, byte[]>opsForStream().range(stream, Range.unbounded());
        return all == null ? List.of() : all.stream()
                .filter(r -> Long.parseLong(new String(r.getValue().get("project_id"))) == projectId)
                .toList();
    }

    private HttpRequest.Builder request(long projectId, String query) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ingest/" + projectId + "/envelope" + query))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpRequest.Builder post(long projectId, String query) {
        return request(projectId, query).POST(HttpRequest.BodyPublishers.ofByteArray(ENVELOPE));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
