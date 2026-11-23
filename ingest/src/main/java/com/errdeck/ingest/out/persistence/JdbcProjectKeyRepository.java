package com.errdeck.ingest.out.persistence;

import com.errdeck.ingest.app.ProjectKeyRepository;
import com.errdeck.ingest.domain.Platform;
import com.errdeck.ingest.domain.ProjectKey;
import java.sql.Array;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectKeyRepository implements ProjectKeyRepository {

    private final JdbcClient jdbc;

    public JdbcProjectKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectKey> findByPublicKey(String publicKey) {
        return jdbc.sql("""
                        SELECT p.id, p.platform, p.rate_limit_per_min, p.allowed_origins, k.revoked_at
                        FROM project_keys k
                        JOIN projects p ON p.id = k.project_id
                        WHERE k.public_key = ?
                        """)
                .param(publicKey)
                .query((rs, rowNum) -> {
                    Array origins = rs.getArray("allowed_origins");
                    Timestamp revokedAt = rs.getTimestamp("revoked_at");
                    return new ProjectKey(
                            rs.getLong("id"),
                            Platform.fromDb(rs.getString("platform")),
                            rs.getInt("rate_limit_per_min"),
                            Set.of((String[]) origins.getArray()),
                            revokedAt == null ? null : revokedAt.toInstant());
                })
                .optional();
    }
}
