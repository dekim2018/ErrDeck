package com.errdeck.api.out.persistence;

import com.errdeck.api.app.WorkspaceRepository;
import com.errdeck.api.domain.Platform;
import com.errdeck.api.domain.ProjectRef;
import com.errdeck.api.domain.Role;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkspaceRepository implements WorkspaceRepository {

    private final JdbcClient jdbc;

    public JdbcWorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Role> role(long userId, long systemId) {
        return jdbc.sql("SELECT role FROM system_members WHERE system_id = ? AND user_id = ?")
                .params(systemId, userId)
                .query((rs, n) -> Role.valueOf(rs.getString(1)))
                .optional();
    }

    @Override
    public Optional<ProjectRef> project(long projectId) {
        return jdbc.sql("SELECT id, system_id FROM projects WHERE id = ?")
                .param(projectId)
                .query((rs, n) -> new ProjectRef(rs.getLong(1), rs.getLong(2)))
                .optional();
    }

    @Override
    public Optional<ProjectRef> issueProject(long issueId) {
        return jdbc.sql("SELECT p.id, p.system_id FROM issues i JOIN projects p ON p.id = i.project_id WHERE i.id = ?")
                .param(issueId)
                .query((rs, n) -> new ProjectRef(rs.getLong(1), rs.getLong(2)))
                .optional();
    }

    @Override
    public long createSystem(String name, long ownerId) {
        long systemId;
        try {
            systemId = jdbc.sql("INSERT INTO systems (name) VALUES (?) RETURNING id").param(name).query(Long.class).single();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new com.errdeck.api.app.ApiException(com.errdeck.api.app.ApiException.Kind.CONFLICT, "이미 있는 시스템 이름이다");
        }
        jdbc.sql("INSERT INTO system_members (system_id, user_id, role) VALUES (?, ?, 'OWNER')").params(systemId, ownerId).update();
        return systemId;
    }

    @Override
    public List<SystemView> systemsOf(long userId) {
        return jdbc.sql("""
                        SELECT s.id, s.name, m.role FROM systems s JOIN system_members m ON m.system_id = s.id
                        WHERE m.user_id = ? ORDER BY s.name
                        """)
                .param(userId)
                .query((rs, n) -> new SystemView(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3))))
                .list();
    }

    @Override
    public List<Member> members(long systemId) {
        return jdbc.sql("""
                        SELECT u.id, u.email, m.role FROM system_members m JOIN users u ON u.id = m.user_id
                        WHERE m.system_id = ? ORDER BY u.email
                        """)
                .param(systemId)
                .query((rs, n) -> new Member(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3))))
                .list();
    }

    @Override
    public void putMember(long systemId, long userId, Role role) {
        jdbc.sql("""
                        INSERT INTO system_members (system_id, user_id, role) VALUES (?, ?, ?)
                        ON CONFLICT (system_id, user_id) DO UPDATE SET role = EXCLUDED.role
                        """)
                .params(systemId, userId, role.name())
                .update();
    }

    @Override
    public void removeMember(long systemId, long userId) {
        jdbc.sql("DELETE FROM system_members WHERE system_id = ? AND user_id = ?").params(systemId, userId).update();
    }

    @Override
    public long createProject(long systemId, String slug, Platform platform, int rateLimitPerMin, List<String> allowedOrigins) {
        return jdbc.sql("""
                        INSERT INTO projects (system_id, slug, platform, rate_limit_per_min, allowed_origins)
                        VALUES (?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(systemId, slug, platform.dbValue(), rateLimitPerMin, allowedOrigins.toArray(String[]::new))
                .query(Long.class)
                .single();
    }

    @Override
    public List<Project> projects(long systemId) {
        return jdbc.sql("SELECT id, system_id, slug, platform, rate_limit_per_min, allowed_origins FROM projects WHERE system_id = ? ORDER BY slug")
                .param(systemId)
                .query((rs, n) -> project(rs))
                .list();
    }

    @Override
    public Project projectDetail(long projectId) {
        return jdbc.sql("SELECT id, system_id, slug, platform, rate_limit_per_min, allowed_origins FROM projects WHERE id = ?")
                .param(projectId)
                .query((rs, n) -> project(rs))
                .single();
    }

    @Override
    public void updateProject(long projectId, Integer rateLimitPerMin, List<String> allowedOrigins) {
        jdbc.sql("""
                        UPDATE projects
                        SET rate_limit_per_min = COALESCE(?, rate_limit_per_min),
                            allowed_origins = COALESCE(?, allowed_origins)
                        WHERE id = ?
                        """)
                .params(rateLimitPerMin, allowedOrigins == null ? null : allowedOrigins.toArray(String[]::new), projectId)
                .update();
    }

    @Override
    public Set<Long> visibleProjectIds(long userId) {
        return new HashSet<>(jdbc.sql("""
                        SELECT p.id FROM projects p JOIN system_members m ON m.system_id = p.system_id WHERE m.user_id = ?
                        """)
                .param(userId)
                .query(Long.class)
                .list());
    }

    @Override
    public long createKey(long projectId, String publicKey) {
        return jdbc.sql("INSERT INTO project_keys (project_id, public_key) VALUES (?, ?) RETURNING id")
                .params(projectId, publicKey)
                .query(Long.class)
                .single();
    }

    @Override
    public List<Key> keys(long projectId) {
        return jdbc.sql("SELECT id, project_id, public_key, created_at, revoked_at FROM project_keys WHERE project_id = ? ORDER BY id")
                .param(projectId)
                .query((rs, n) -> new Key(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
                        instant(rs.getTimestamp(5))))
                .list();
    }

    @Override
    public boolean revokeKey(long projectId, long keyId, Instant revokedAt) {
        return jdbc.sql("UPDATE project_keys SET revoked_at = ? WHERE id = ? AND project_id = ? AND revoked_at IS NULL")
                .params(Timestamp.from(revokedAt), keyId, projectId)
                .update() > 0;
    }

    @Override
    public boolean updateIssueStatus(long issueId, String status, String resolvedInRelease, Instant ignoredUntil, Long ignoredUntilCount) {
        return jdbc.sql("""
                        UPDATE issues SET status = ?, resolved_in_release = ?, ignored_until = ?, ignored_until_count = ?, ignored_count = 0,
                            updated_at = now()
                        WHERE id = ?
                        """)
                .params(status, resolvedInRelease, ignoredUntil == null ? null : Timestamp.from(ignoredUntil), ignoredUntilCount, issueId)
                .update() > 0;
    }

    @Override
    public long addComment(long issueId, long userId, String body) {
        return jdbc.sql("INSERT INTO issue_comments (issue_id, user_id, body) VALUES (?, ?, ?) RETURNING id")
                .params(issueId, userId, body)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Long> userIdByEmail(String email) {
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).optional();
    }

    private static Project project(ResultSet rs) throws SQLException {
        Array origins = rs.getArray("allowed_origins");
        return new Project(rs.getLong("id"), rs.getLong("system_id"), rs.getString("slug"), Platform.fromDb(rs.getString("platform")),
                rs.getInt("rate_limit_per_min"), List.of((String[]) origins.getArray()));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
