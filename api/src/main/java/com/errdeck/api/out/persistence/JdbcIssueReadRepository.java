package com.errdeck.api.out.persistence;

import com.errdeck.api.app.IssueReadRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIssueReadRepository implements IssueReadRepository {

    /** 이슈와 그 지문 목록. 지문은 CHAR(32) 라 뒤 공백이 없지만 혹시 몰라 자른다. */
    private static final String SELECT = """
            SELECT i.id, i.project_id, i.status, i.title, i.culprit, i.first_seen, i.last_seen, i.last_release,
                   i.resolved_in_release, i.ignored_until,
                   ARRAY(SELECT rtrim(f.fingerprint) FROM issue_fingerprints f WHERE f.issue_id = i.id ORDER BY f.created_at) AS fingerprints
            FROM issues i
            """;

    private final JdbcClient jdbc;

    public JdbcIssueReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IssueRow> recent(long projectId, String status, String release, int limit, int offset) {
        // 목록 정렬용 색인 (project_id, status, last_seen DESC) 를 탄다(10.1).
        return jdbc.sql(SELECT + """
                        WHERE i.project_id = ? AND (?::text IS NULL OR i.status = ?) AND (?::text IS NULL OR i.last_release = ?)
                        ORDER BY i.last_seen DESC, i.id DESC LIMIT ? OFFSET ?
                        """)
                .params(projectId, status, status, release, release, limit, offset)
                .query((rs, n) -> row(rs))
                .list();
    }

    @Override
    public List<IssueRow> byFingerprints(long projectId, Collection<String> fingerprints, String status, String release) {
        return jdbc.sql(SELECT + """
                        WHERE i.project_id = ? AND (?::text IS NULL OR i.status = ?) AND (?::text IS NULL OR i.last_release = ?)
                          AND i.id IN (SELECT f.issue_id FROM issue_fingerprints f WHERE f.project_id = ? AND f.fingerprint = ANY(?))
                        """)
                .params(projectId, status, status, release, release, projectId, fingerprints.toArray(String[]::new))
                .query((rs, n) -> row(rs))
                .list();
    }

    @Override
    public Optional<IssueRow> find(long issueId) {
        return jdbc.sql(SELECT + " WHERE i.id = ?").param(issueId).query((rs, n) -> row(rs)).optional();
    }

    @Override
    public List<Comment> comments(long issueId) {
        return jdbc.sql("""
                        SELECT c.id, u.email, c.body, c.created_at FROM issue_comments c JOIN users u ON u.id = c.user_id
                        WHERE c.issue_id = ? ORDER BY c.created_at, c.id
                        """)
                .param(issueId)
                .query((rs, n) -> new Comment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                .list();
    }

    @Override
    public Map<String, Long> issueIdsByFingerprint(long projectId, Collection<String> fingerprints) {
        Map<String, Long> map = new HashMap<>();
        jdbc.sql("SELECT rtrim(fingerprint), issue_id FROM issue_fingerprints WHERE project_id = ? AND fingerprint = ANY(?)")
                .params(projectId, fingerprints.toArray(String[]::new))
                .query(rs -> {
                    map.put(rs.getString(1), rs.getLong(2));
                });
        return map;
    }

    @Override
    public String projectSlug(long projectId) {
        return jdbc.sql("SELECT slug FROM projects WHERE id = ?").param(projectId).query(String.class).single();
    }

    private static IssueRow row(ResultSet rs) throws SQLException {
        Array fps = rs.getArray("fingerprints");
        List<String> fingerprints = new ArrayList<>(List.of((String[]) fps.getArray()));
        return new IssueRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("status"), rs.getString("title"),
                rs.getString("culprit"), instant(rs.getTimestamp("first_seen")), instant(rs.getTimestamp("last_seen")),
                rs.getString("last_release"), rs.getString("resolved_in_release"), instant(rs.getTimestamp("ignored_until")),
                fingerprints);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
