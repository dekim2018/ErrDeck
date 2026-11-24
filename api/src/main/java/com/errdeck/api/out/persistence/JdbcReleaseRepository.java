package com.errdeck.api.out.persistence;

import com.errdeck.api.app.ReleaseRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReleaseRepository implements ReleaseRepository {

    private final JdbcClient jdbc;

    public JdbcReleaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Release upsertRelease(long projectId, String version) {
        // 이미 있으면 바꾸지 않는다. created_at 이 재발 판정의 순서 기준이라(6.6) 다시 올려도 앞당겨지면 안 된다.
        jdbc.sql("INSERT INTO releases (project_id, version) VALUES (?, ?) ON CONFLICT (project_id, version) DO NOTHING")
                .params(projectId, version).update();
        return findRelease(projectId, version).orElseThrow();
    }

    @Override
    public Optional<Release> findRelease(long projectId, String version) {
        return jdbc.sql("SELECT id, version, created_at FROM releases WHERE project_id = ? AND version = ?")
                .params(projectId, version)
                .query((rs, n) -> new Release(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()))
                .optional();
    }

    @Override
    public void upsertArtifact(long releaseId, String name, long size, String sha256, String path) {
        jdbc.sql("""
                        INSERT INTO release_artifacts (release_id, name, size, sha256, path) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (release_id, name) DO UPDATE SET size = EXCLUDED.size, sha256 = EXCLUDED.sha256, path = EXCLUDED.path,
                            created_at = now()
                        """)
                .params(releaseId, name, size, sha256, path).update();
    }

    @Override
    public List<ReleaseSummary> releases(long projectId, int limit) {
        return jdbc.sql("""
                        SELECT r.version, r.created_at,
                               (SELECT count(*) FROM release_artifacts a WHERE a.release_id = r.id) AS artifacts,
                               (SELECT count(*) FROM issues i WHERE i.project_id = r.project_id AND i.first_release = r.version) AS new_issues,
                               (SELECT count(*) FROM issues i WHERE i.project_id = r.project_id AND i.status = 'regressed'
                                  AND i.last_release = r.version) AS regressed
                        FROM releases r WHERE r.project_id = ? ORDER BY r.created_at DESC, r.id DESC LIMIT ?
                        """)
                .params(projectId, limit)
                .query((rs, n) -> new ReleaseSummary(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getInt(3), rs.getLong(4), rs.getLong(5)))
                .list();
    }

    @Override
    public long createToken(long projectId, String name, String tokenHash) {
        return jdbc.sql("INSERT INTO api_tokens (project_id, name, token_hash) VALUES (?, ?, ?) RETURNING id")
                .params(projectId, name, tokenHash).query(Long.class).single();
    }

    @Override
    public List<Token> tokens(long projectId) {
        return jdbc.sql("SELECT id, name, created_at, last_used_at, revoked_at FROM api_tokens WHERE project_id = ? ORDER BY id")
                .param(projectId)
                .query((rs, n) -> new Token(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), instant(rs.getTimestamp(4)),
                        instant(rs.getTimestamp(5))))
                .list();
    }

    @Override
    public boolean revokeToken(long projectId, long tokenId) {
        return jdbc.sql("UPDATE api_tokens SET revoked_at = now() WHERE id = ? AND project_id = ? AND revoked_at IS NULL")
                .params(tokenId, projectId).update() > 0;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
