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

/** 이슈 목록은 PostgreSQL 의 이슈에 ClickHouse 의 횟수를 붙이고, 상세는 최근 원본과 추이를 준다(6.5, 12.3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssueQueryTest extends TestInfra {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;
    private String token;
    private long projectId;
    private long frequent;
    private long recent;
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

        String fpA = fingerprint('a');
        String fpB = fingerprint('b');
        frequent = issue("java.lang.IllegalStateException: 많이 남", "now() - interval '10 minutes'", fpA);
        recent = issue("java.lang.ArithmeticException: 최근에 남", "now() - interval '1 minute'", fpB);
        admin.execute("errdeck", """
                INSERT INTO event_ticks (project_id, fingerprint, timestamp, count, user_hash) VALUES
                (%1$d, '%2$s', now64(3) - 600, 40, 1), (%1$d, '%2$s', now64(3) - 300, 60, 2),
                (%1$d, '%3$s', now64(3) - 60, 3, 1), (%1$d, '%3$s', now64(3) - 60, 1, 2), (%1$d, '%3$s', now64(3) - 60, 1, 3)
                """.formatted(projectId, fpA, fpB));
        admin.execute("errdeck", """
                INSERT INTO events (project_id, event_id, fingerprint, timestamp, received_at, environment, release, level, exc_type,
                                    message, payload)
                VALUES (%d, generateUUIDv4(), '%s', now64(3) - 300, now64(3), 'prod', 'r7', 'error', 'java.lang.IllegalStateException',
                        '많이 남', '{"event_id":"x","exceptions":[{"type":"java.lang.IllegalStateException","value":"많이 남"}]}')
                """.formatted(projectId, fpA));
    }

    @Test
    void 기본은_최근_발생_순이고_횟수와_영향_사용자를_붙인다() {
        List<Object> items = api.get("/api/projects/" + projectId + "/issues", token).jsonList();

        assertThat(items).extracting(i -> ((Number) ((Map<?, ?>) i).get("id")).longValue()).containsExactly(recent, frequent);
        Map<?, ?> first = (Map<?, ?>) items.getFirst();
        assertThat(((Number) first.get("count")).longValue()).isEqualTo(5);
        assertThat(((Number) first.get("users")).longValue()).isEqualTo(3);
    }

    @Test
    void 횟수순과_영향_사용자순으로_정렬한다() {
        assertThat(ids("?sort=frequent")).containsExactly(frequent, recent);
        assertThat(ids("?sort=users")).containsExactly(recent, frequent);
    }

    @Test
    void 상태로_거른다() {
        jdbc.sql("UPDATE issues SET status = 'resolved' WHERE id = ?").param(recent).update();

        assertThat(ids("?status=unresolved")).containsExactly(frequent);
        assertThat(ids("?status=resolved&sort=frequent")).containsExactly(recent);
    }

    @Test
    void 상세는_최근_원본과_추이와_그라파나_링크를_준다() {
        Map<String, Object> detail = api.get("/api/issues/" + frequent, token).json();

        @SuppressWarnings("unchecked")
        Map<String, Object> issue = (Map<String, Object>) detail.get("issue");
        assertThat(((Number) issue.get("count")).longValue()).isEqualTo(100);
        assertThat(detail.get("latestEnvironment")).isEqualTo("prod");
        assertThat(((Map<?, ?>) detail.get("latestEvent")).get("exceptions")).isInstanceOf(List.class);
        assertThat(((List<?>) detail.get("trend"))).isNotEmpty();
        assertThat((String) detail.get("grafanaUrl")).contains("/d/errdeck-jvm?var-job=p-");
    }

    @Test
    void 구성원이_아니면_목록도_상세도_볼_수_없다() {
        String outsider = api.signupAndLogin("x-" + UUID.randomUUID() + "@example.com");

        assertThat(api.get("/api/projects/" + projectId + "/issues", outsider).status()).isEqualTo(404);
        assertThat(api.get("/api/issues/" + frequent, outsider).status()).isEqualTo(404);
    }

    @Test
    void 틀린_기간과_정렬은_400이다() {
        assertThat(api.get("/api/projects/" + projectId + "/issues?period=90d", token).status()).isEqualTo(400);
        assertThat(api.get("/api/projects/" + projectId + "/issues?sort=random", token).status()).isEqualTo(400);
    }

    private List<Long> ids(String query) {
        return api.get("/api/projects/" + projectId + "/issues" + query, token).jsonList().stream()
                .map(i -> ((Number) ((Map<?, ?>) i).get("id")).longValue()).toList();
    }

    private long issue(String title, String lastSeen, String fingerprint) {
        long id = jdbc.sql("INSERT INTO issues (project_id, title, first_seen, last_seen) VALUES (?, ?, now() - interval '1 hour', "
                        + lastSeen + ") RETURNING id")
                .params(projectId, title).query(Long.class).single();
        jdbc.sql("INSERT INTO issue_fingerprints (project_id, fingerprint, issue_id, rule_version) VALUES (?, ?, ?, 1)")
                .params(projectId, fingerprint, id).update();
        return id;
    }

    private String fingerprint(char c) {
        return Long.toHexString(projectId) + String.valueOf(c).repeat(32 - Long.toHexString(projectId).length());
    }
}
