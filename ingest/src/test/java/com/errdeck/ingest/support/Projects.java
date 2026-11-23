package com.errdeck.ingest.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 테스트마다 새 프로젝트와 키를 만든다. 무작위 값을 쓰므로 테스트끼리 한도와 캐시가 섞이지 않는다. */
public final class Projects {

    public record Created(long projectId, String publicKey) {
    }

    private final JdbcClient jdbc;

    public Projects(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Created spring(int rateLimitPerMin) {
        return create("spring", rateLimitPerMin, List.of(), null);
    }

    public Created vue(List<String> allowedOrigins) {
        return create("vue", 6000, allowedOrigins, null);
    }

    public Created create(String platform, int rateLimitPerMin, List<String> allowedOrigins, Instant revokedAt) {
        long systemId = jdbc.sql("INSERT INTO systems (name) VALUES (?) RETURNING id")
                .param("s-" + UUID.randomUUID())
                .query(Long.class)
                .single();
        long projectId = jdbc.sql("""
                        INSERT INTO projects (system_id, slug, platform, rate_limit_per_min, allowed_origins)
                        VALUES (?, ?, ?, ?, ?::text[]) RETURNING id
                        """)
                .params(systemId, "p-" + UUID.randomUUID(), platform, rateLimitPerMin, "{" + String.join(",", allowedOrigins) + "}")
                .query(Long.class)
                .single();
        String key = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO project_keys (project_id, public_key, revoked_at) VALUES (?, ?, ?)")
                .params(projectId, key, revokedAt == null ? null : java.sql.Timestamp.from(revokedAt))
                .update();
        return new Created(projectId, key);
    }
}
