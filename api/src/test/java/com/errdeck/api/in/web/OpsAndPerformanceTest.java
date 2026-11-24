package com.errdeck.api.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.out.clickhouse.ClickHouseHttp;
import com.errdeck.api.support.ApiClient;
import com.errdeck.api.support.TestInfra;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 모니터와 알림 규칙 관리(9장, 16장), 통합 화면(12장), 성능 화면과 추적(8장). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 프로메테우스가 없는 주소. 요약 카드는 비고 화면은 떠야 한다.
        properties = {"errdeck.prometheus.url=http://127.0.0.1:1", "errdeck.uptime.allowed-hosts=demo-api"})
class OpsAndPerformanceTest extends TestInfra {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;
    private String token;
    private long projectId;
    private final ClickHouseHttp admin = new ClickHouseHttp(URI.create(clickHouseUrl()), "errdeck", "errdeck", Duration.ofSeconds(10));

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "o-" + suffix + "@example.com";
        token = api.signupAndLogin(email);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE email = ?").param(email).update();
        long systemId = ((Number) api.post("/api/systems", Map.of("name", "s-" + suffix), token).json().get("id")).longValue();
        projectId = ((Number) api.post("/api/systems/" + systemId + "/projects", Map.of("slug", "p-" + suffix, "platform", "SPRING"), token)
                .json().get("id")).longValue();
    }

    @Test
    void 내부_주소를_넣은_모니터와_웹훅은_거부한다() {
        for (String url : List.of("http://169.254.169.254/latest/meta-data", "http://localhost:6379/", "http://10.0.0.1/")) {
            ApiClient.Response r = api.post("/api/projects/" + projectId + "/monitors", Map.of("url", url), token);
            assertThat(r.status()).as(url).isEqualTo(400);
        }
        assertThat(api.post("/api/projects/" + projectId + "/alert-rules",
                Map.of("kind", "new_issue", "channel", "discord", "target", "http://127.0.0.1/hook"), token).status()).isEqualTo(400);
    }

    @Test
    void 모니터를_만들고_중지하고_다시_켠다() {
        long id = ((Number) api.post("/api/projects/" + projectId + "/monitors",
                Map.of("name", "데모", "url", "http://demo-api:8090/actuator/health", "intervalSeconds", 30), token).json().get("id")).longValue();

        assertThat(api.patch("/api/monitors/" + id, Map.of("paused", true), token).status()).isEqualTo(204);
        Map<?, ?> paused = (Map<?, ?>) api.get("/api/projects/" + projectId + "/monitors", token).jsonList().getFirst();
        assertThat(paused.get("pausedUntil")).isNotNull();
        assertThat(paused.get("status")).isEqualTo("unknown");

        api.patch("/api/monitors/" + id, Map.of("paused", false), token);
        assertThat(((Map<?, ?>) api.get("/api/projects/" + projectId + "/monitors", token).jsonList().getFirst()).get("pausedUntil")).isNull();
        assertThat(api.get("/api/monitors/" + id + "/incidents", token).jsonList()).isEmpty();
    }

    @Test
    void 알림_규칙의_웹훅_주소는_목록에서_가린다() {
        api.post("/api/projects/" + projectId + "/alert-rules", Map.of("kind", "frequency", "channel", "slack",
                "target", "https://hooks.slack.com/services/T000/B000/secret", "threshold", 100, "windowMinutes", 5), token);

        Map<?, ?> rule = (Map<?, ?>) api.get("/api/projects/" + projectId + "/alert-rules", token).jsonList().getFirst();
        assertThat((String) rule.get("target")).isEqualTo("https://hooks.slack.com/…");
        assertThat(api.post("/api/projects/" + projectId + "/alert-rules", Map.of("kind", "frequency", "channel", "slack",
                "target", "https://hooks.slack.com/x"), token).status()).isEqualTo(400);
    }

    @Test
    void 프로메테우스가_없어도_통합_화면이_뜨고_재발이_있으면_주의다() {
        jdbc.sql("INSERT INTO issues (project_id, title, first_seen, last_seen, status) VALUES (?, '재발한 것', now(), now(), 'regressed')")
                .param(projectId).update();

        Map<String, Object> overview = api.get("/api/overview", token).json();

        assertThat(overview.get("metricsAvailable")).isEqualTo(false);
        Map<?, ?> system = (Map<?, ?>) ((List<?>) overview.get("systems")).getFirst();
        Map<?, ?> project = (Map<?, ?>) ((List<?>) system.get("projects")).getFirst();
        assertThat(project.get("signal")).isEqualTo("WARN");
        assertThat(project.get("card")).isNull();
        assertThat(system.get("signal")).isEqualTo("WARN");
    }

    @Test
    void 서비스_두_개를_지난_요청을_trace_id_로_찾고_표본_엔드포인트_표를_만든다() {
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        admin.execute("errdeck", """
                INSERT INTO transactions (project_id, trace_id, span_id, parent_span_id, name, op, timestamp, received_at, duration_ms,
                                          status, partial, environment, release, span_ids, span_parent_ids, span_ops, span_descriptions,
                                          span_starts_ms, span_durations_ms, span_repeats)
                VALUES (%1$d, '%2$s', 'aaaaaaaaaaaaaaaa', '0000000000000000', 'GET /demo/chain/{id}', 'http.server', now64(3) - 10, now64(3),
                        120, '200', 0, 'test', 'r1', ['bbbbbbbbbbbbbbbb'], ['aaaaaaaaaaaaaaaa'], ['http.client'], ['GET demo/items/1'],
                        [5], [100], [1]),
                       (%1$d, '%2$s', 'cccccccccccccccc', 'bbbbbbbbbbbbbbbb', 'GET /demo/items/{id}', 'http.server', now64(3) - 9.99, now64(3),
                        90, '200', 0, 'test', 'r1', ['dddddddddddddddd'], ['cccccccccccccccc'], ['db.sql'], ['SELECT * FROM item WHERE id = ?'],
                        [2], [80], [12])
                """.formatted(projectId, trace));

        List<Object> found = api.get("/api/traces/" + trace, token).jsonList();
        assertThat(found).hasSize(2);
        Map<?, ?> back = (Map<?, ?>) found.get(1);
        assertThat(back.get("parentSpanId")).isEqualTo("bbbbbbbbbbbbbbbb");
        assertThat(((Map<?, ?>) ((List<?>) back.get("spans")).getFirst()).get("repeat")).isEqualTo(12L);
        assertThat(((Map<?, ?>) found.getFirst()).get("parentSpanId")).isNull();

        Map<String, Object> table = api.get("/api/projects/" + projectId + "/performance/endpoints?period=1h", token).json();
        assertThat(table.get("source")).isEqualTo("SAMPLES");
        assertThat((List<?>) table.get("endpoints")).hasSize(2);
        assertThat(api.get("/api/projects/" + projectId + "/performance/transactions?name=GET%20/demo/items/%7Bid%7D", token).jsonList())
                .hasSize(1);
        assertThat(api.get("/api/traces/not-a-trace", token).status()).isEqualTo(400);
    }
}
