package com.errdeck.api.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.support.ApiClient;
import com.errdeck.api.support.TestInfra;
import com.errdeck.core.files.LocalArtifactStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 프로젝트 토큰 발급, 빌드 스크립트의 릴리스 등록과 소스맵 업로드, 릴리스 목록(7.2, 15.2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReleaseFlowTest extends TestInfra {

    private static final String MAP = """
            {"version":3,"sources":["../../src/a.ts"],"names":[],"mappings":"AAAA","sourcesContent":["const a = 1"]}
            """;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;
    private String owner;
    private long projectId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "o-" + suffix + "@example.com";
        owner = api.signupAndLogin(email);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE email = ?").param(email).update();
        long systemId = ((Number) api.post("/api/systems", Map.of("name", "s-" + suffix), owner).json().get("id")).longValue();
        projectId = ((Number) api.post("/api/systems/" + systemId + "/projects", Map.of("slug", "web-" + suffix, "platform", "VUE"), owner)
                .json().get("id")).longValue();
    }

    @Test
    void 토큰으로_릴리스를_등록하고_소스맵을_올리면_볼륨에_저장되고_목록에_보인다() throws Exception {
        String token = token();

        assertThat(api.post("/api/projects/" + projectId + "/releases", Map.of("version", "web@1"), token).status()).isEqualTo(200);
        assertThat(upload(token, "web@1", "assets/index-a1.js.map", MAP)).isEqualTo(204);
        assertThat(upload(token, "web@2", "assets/index-b2.js.map", MAP)).isEqualTo(204);

        assertThat(new LocalArtifactStore(FILES).get(projectId, "web@1", "assets/index-a1.js.map"))
                .hasValueSatisfying(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(MAP));
        List<Object> releases = api.get("/api/projects/" + projectId + "/releases", owner).jsonList();
        List<String> versions = releases.stream().map(r -> (String) ((Map<?, ?>) r).get("version")).toList();
        assertThat(versions).containsExactly("web@2", "web@1");
        assertThat(((Number) ((Map<?, ?>) releases.get(1)).get("artifacts")).intValue()).isEqualTo(1);
    }

    @Test
    void 같은_릴리스를_다시_등록해도_순서가_바뀌지_않는다() throws Exception {
        String token = token();
        String first = (String) api.post("/api/projects/" + projectId + "/releases", Map.of("version", "web@1"), token).json().get("createdAt");
        Thread.sleep(20);

        String again = (String) api.post("/api/projects/" + projectId + "/releases", Map.of("version", "web@1"), token).json().get("createdAt");

        assertThat(again).isEqualTo(first);
    }

    @Test
    void 이름이나_내용이_틀린_소스맵은_400이다() throws Exception {
        String token = token();

        assertThat(upload(token, "web@1", "../etc/passwd.map", MAP)).isEqualTo(400);
        assertThat(upload(token, "web@1", "assets/index.js", MAP)).isEqualTo(400);
        assertThat(upload(token, "web@1", "assets/index.js.map", "not a sourcemap")).isEqualTo(400);
        assertThat(upload(token, "../bad", "assets/index.js.map", MAP)).isIn(400, 404);
    }

    @Test
    void 폐기한_토큰이나_콘솔_토큰으로는_올릴_수_없다() throws Exception {
        Map<String, Object> issued = api.post("/api/projects/" + projectId + "/tokens", Map.of("name", "ci"), owner).json();
        String token = (String) issued.get("token");
        long tokenId = ((Number) issued.get("id")).longValue();

        assertThat(api.delete("/api/projects/" + projectId + "/tokens/" + tokenId, owner).status()).isEqualTo(204);

        assertThat(upload(token, "web@1", "assets/index.js.map", MAP)).isEqualTo(401);
        assertThat(upload(owner, "web@1", "assets/index.js.map", MAP)).isEqualTo(401);
        assertThat(api.get("/api/projects/" + projectId + "/tokens", owner).jsonList()).hasSize(1);
    }

    @Test
    void 토큰_원문은_발급할_때만_보이고_목록에는_없다() {
        String token = token();

        String list = api.get("/api/projects/" + projectId + "/tokens", owner).body();

        assertThat(token).startsWith("edt_");
        assertThat(list).doesNotContain(token);
    }

    private String token() {
        return (String) api.post("/api/projects/" + projectId + "/tokens", Map.of("name", "ci-" + UUID.randomUUID()), owner).json().get("token");
    }

    private int upload(String token, String version, String name, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projects/" + projectId + "/releases/"
                                    + version + "/artifacts?name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
