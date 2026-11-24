package com.errdeck.api.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.app.AccessPolicy;
import com.errdeck.api.domain.Role;
import com.errdeck.api.support.ApiClient;
import com.errdeck.api.support.TestInfra;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.4 권한 표의 칸마다 역할 4개로 실제 요청을 보낸다.
 *
 * <p>"알림 규칙, 모니터, 릴리스" 칸의 API 는 15, 20, 21 작업에서 생긴다. 그때까지는 같은 권한 검사({@link AccessPolicy})를 거치는
 * 테스트용 엔드포인트로 그 칸을 확인한다(CHOICES 11-4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PermissionMatrixTest.AdminColumn.class)
class PermissionMatrixTest extends TestInfra {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;
    private long systemId;
    private long projectId;
    private long issueId;
    private final java.util.EnumMap<Role, String> tokens = new java.util.EnumMap<>(Role.class);
    private final java.util.EnumMap<Role, String> emails = new java.util.EnumMap<>(Role.class);
    private String outsider;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        for (Role role : Role.values()) {
            emails.put(role, role.name().toLowerCase() + "-" + suffix + "@example.com");
            tokens.put(role, api.signupAndLogin(emails.get(role)));
        }
        outsider = api.signupAndLogin("outsider-" + suffix + "@example.com");
        makeAdmin(emails.get(Role.OWNER));

        systemId = ((Number) api.post("/api/systems", Map.of("name", "시스템-" + suffix), tokens.get(Role.OWNER)).json().get("id")).longValue();
        for (Role role : List.of(Role.VIEWER, Role.MEMBER, Role.ADMIN)) {
            assertThat(api.put("/api/systems/" + systemId + "/members", Map.of("email", emails.get(role), "role", role.name()),
                    tokens.get(Role.OWNER)).status()).isEqualTo(204);
        }
        projectId = ((Number) api.post("/api/systems/" + systemId + "/projects",
                Map.of("slug", "p-" + suffix, "platform", "SPRING"), tokens.get(Role.OWNER)).json().get("id")).longValue();
        issueId = jdbc.sql("""
                        INSERT INTO issues (project_id, title, first_seen, last_seen) VALUES (?, 'E: x', now(), now()) RETURNING id
                        """)
                .param(projectId).query(Long.class).single();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 조회는_모든_역할이_할_수_있다(Role role) {
        assertThat(api.get("/api/systems/" + systemId + "/projects", tokens.get(role)).status()).isEqualTo(200);
        assertThat(api.get("/api/projects/" + projectId, tokens.get(role)).status()).isEqualTo(200);
        assertThat(api.get("/api/systems/" + systemId + "/members", tokens.get(role)).status()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 이슈_상태_변경과_댓글은_MEMBER_이상이다(Role role) {
        int expected = role.atLeast(Role.MEMBER) ? 2 : 4;

        int status = api.patch("/api/issues/" + issueId, Map.of("status", "resolved"), tokens.get(role)).status();
        int comment = api.post("/api/issues/" + issueId + "/comments", Map.of("body", "확인함"), tokens.get(role)).status();

        assertThat(status / 100).isEqualTo(expected);
        assertThat(comment / 100).isEqualTo(expected);
        if (!role.atLeast(Role.MEMBER)) {
            assertThat(status).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 알림_규칙_모니터_릴리스는_ADMIN_이상이다(Role role) {
        int status = api.post("/api/test/projects/" + projectId + "/admin-action", Map.of(), tokens.get(role)).status();

        assertThat(status).isEqualTo(role.atLeast(Role.ADMIN) ? 200 : 403);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 키와_토큰_구성원_한도는_OWNER_만_할_수_있다(Role role) {
        int expected = role == Role.OWNER ? 2 : 4;

        assertThat(api.post("/api/projects/" + projectId + "/keys", Map.of(), tokens.get(role)).status() / 100).isEqualTo(expected);
        assertThat(api.get("/api/projects/" + projectId + "/keys", tokens.get(role)).status() / 100).isEqualTo(expected);
        assertThat(api.patch("/api/projects/" + projectId, Map.of("rateLimitPerMin", 1000), tokens.get(role)).status() / 100)
                .isEqualTo(expected);
        assertThat(api.post("/api/systems/" + systemId + "/projects", Map.of("slug", "q-" + role.name().toLowerCase() + "-x",
                "platform", "VUE"), tokens.get(role)).status() / 100).isEqualTo(expected);
        String newcomer = "new-" + UUID.randomUUID() + "@example.com";
        api.signupAndLogin(newcomer);
        assertThat(api.put("/api/systems/" + systemId + "/members", Map.of("email", newcomer, "role", "VIEWER"), tokens.get(role))
                .status() / 100).isEqualTo(expected);
    }

    @Test
    void 구성원이_아니면_다른_시스템의_것은_있는지조차_알_수_없다() {
        assertThat(api.get("/api/systems/" + systemId + "/projects", outsider).status()).isEqualTo(404);
        assertThat(api.get("/api/projects/" + projectId, outsider).status()).isEqualTo(404);
        assertThat(api.patch("/api/issues/" + issueId, Map.of("status", "resolved"), outsider).status()).isEqualTo(404);
        assertThat(api.post("/api/projects/" + projectId + "/keys", Map.of(), outsider).status()).isEqualTo(404);
        assertThat(api.get("/api/systems", outsider).jsonList()).isEmpty();
    }

    @Test
    void 관리자가_아니면_시스템을_만들_수_없다() {
        assertThat(api.post("/api/systems", Map.of("name", "x-" + UUID.randomUUID()), tokens.get(Role.ADMIN)).status()).isEqualTo(403);
    }

    @Test
    void 마지막_OWNER_는_빠지거나_낮아질_수_없다() {
        long ownerId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(emails.get(Role.OWNER)).query(Long.class).single();

        assertThat(api.delete("/api/systems/" + systemId + "/members/" + ownerId, tokens.get(Role.OWNER)).status()).isEqualTo(409);
        assertThat(api.put("/api/systems/" + systemId + "/members", Map.of("email", emails.get(Role.OWNER), "role", "ADMIN"),
                tokens.get(Role.OWNER)).status()).isEqualTo(409);
    }

    @Test
    void 키를_만들면_DSN을_주고_폐기하면_더는_쓸_수_없다() {
        Map<String, Object> created = api.post("/api/projects/" + projectId + "/keys", Map.of(), tokens.get(Role.OWNER)).json();
        String dsn = (String) created.get("dsn");
        @SuppressWarnings("unchecked")
        long keyId = ((Number) ((Map<String, Object>) created.get("key")).get("id")).longValue();

        assertThat(dsn).matches("http://[0-9a-f]{32}@localhost:8080/" + projectId);
        assertThat(api.delete("/api/projects/" + projectId + "/keys/" + keyId, tokens.get(Role.OWNER)).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM project_keys WHERE id = ?").param(keyId).query(Boolean.class).single())
                .isTrue();
    }

    private void makeAdmin(String email) {
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE email = ?").param(email).update();
    }

    @TestConfiguration
    @RestController
    static class AdminColumn {

        private final AccessPolicy access;

        AdminColumn(AccessPolicy access) {
            this.access = access;
        }

        @PostMapping("/api/test/projects/{projectId}/admin-action")
        Map<String, Long> adminAction(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
            access.requireProject(Long.parseLong(jwt.getSubject()), projectId, Role.ADMIN);
            return Map.of("projectId", projectId);
        }
    }
}
