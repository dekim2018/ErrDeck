package com.errdeck.api.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.app.AuthService;
import com.errdeck.api.security.ProjectTokenFilter;
import com.errdeck.api.support.ApiClient;
import com.errdeck.api.support.TestInfra;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 필터 체인 셋(15.2)이 자기 경로만 맡는지 본다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "errdeck.auth.internal-secret=test-secret")
@Import(FilterChainTest.ReleaseProbe.class)
class FilterChainTest extends TestInfra {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void 내부_경로는_공유_비밀이_맞을_때만_받는다() {
        Map<String, Object> alert = Map.of("status", "firing", "alerts", java.util.List.of());

        assertThat(api.post("/api/internal/alerts", alert, null).status()).isEqualTo(401);
        assertThat(api.send("POST", "/api/internal/alerts", alert, null, null, Map.of("X-ErrDeck-Internal-Secret", "wrong")).status())
                .isEqualTo(401);
        assertThat(api.send("POST", "/api/internal/alerts", alert, null, null, Map.of("X-ErrDeck-Internal-Secret", "test-secret"))
                .status()).isEqualTo(202);
    }

    @Test
    void 내부_경로는_콘솔_사용자의_토큰으로_들어갈_수_없다() {
        String token = api.signupAndLogin("u-" + UUID.randomUUID() + "@example.com");

        assertThat(api.post("/api/internal/alerts", Map.of(), token).status()).isEqualTo(401);
    }

    @Test
    void 릴리스_쓰기는_그_프로젝트의_토큰으로만_한다() {
        long projectA = project();
        long projectB = project();
        String tokenA = token(projectA, false);
        String revoked = token(projectA, true);

        assertThat(api.post("/api/projects/" + projectA + "/releases/probe", Map.of(), null).status()).isEqualTo(401);
        assertThat(api.post("/api/projects/" + projectA + "/releases/probe", Map.of(), "unknown-token").status()).isEqualTo(401);
        assertThat(api.post("/api/projects/" + projectA + "/releases/probe", Map.of(), revoked).status()).isEqualTo(401);
        assertThat(api.post("/api/projects/" + projectB + "/releases/probe", Map.of(), tokenA).status()).isEqualTo(403);
        ApiClient.Response ok = api.post("/api/projects/" + projectA + "/releases/probe", Map.of(), tokenA);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(((Number) ok.json().get("projectId")).longValue()).isEqualTo(projectA);
    }

    @Test
    void 프로젝트_토큰으로는_다른_콘솔_API를_부를_수_없다() {
        long project = project();
        String token = token(project, false);

        assertThat(api.get("/api/projects/" + project, token).status()).isEqualTo(401);
        assertThat(api.get("/api/projects/" + project + "/releases", token).status()).isEqualTo(401);
    }

    @Test
    void 상태_확인은_열려_있고_그_밖의_경로는_막힌다() {
        assertThat(api.get("/actuator/health", null).status()).isEqualTo(200);
        assertThat(api.get("/actuator/env", null).status()).isIn(401, 403);
        assertThat(api.get("/anything", null).status()).isIn(401, 403);
    }

    private long project() {
        long systemId = jdbc.sql("INSERT INTO systems (name) VALUES (?) RETURNING id").param("s-" + UUID.randomUUID())
                .query(Long.class).single();
        return jdbc.sql("INSERT INTO projects (system_id, slug, platform) VALUES (?, ?, 'vue') RETURNING id")
                .params(systemId, "p-" + UUID.randomUUID()).query(Long.class).single();
    }

    private String token(long projectId, boolean revoked) {
        String token = "tok-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO api_tokens (project_id, name, token_hash, revoked_at) VALUES (?, 'ci', ?, " + (revoked ? "now()" : "NULL") + ")")
                .params(projectId, AuthService.hash(token)).update();
        return token;
    }

    @TestConfiguration
    @RestController
    static class ReleaseProbe {

        @PostMapping("/api/projects/{projectId}/releases/probe")
        Map<String, Object> probe(@PathVariable long projectId, HttpServletRequest request) {
            return Map.of("projectId", request.getAttribute(ProjectTokenFilter.PROJECT_ID));
        }
    }
}
