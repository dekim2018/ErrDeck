package com.errdeck.worker.out.persistence;

import com.errdeck.worker.app.IssueRepository;
import com.errdeck.worker.domain.IssueKey;
import com.errdeck.worker.domain.IssueSeen;
import com.errdeck.worker.domain.NewIssue;
import com.errdeck.worker.domain.Quarantine;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcIssueRepository implements IssueRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;

    public JdbcIssueRepository(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = transactions;
    }

    @Override
    public Map<IssueKey, Long> findIssueIds(Collection<IssueKey> keys) {
        Map<IssueKey, Long> found = new HashMap<>();
        if (keys.isEmpty()) {
            return found;
        }
        List<Long> projectIds = new ArrayList<>(keys.size());
        List<String> fingerprints = new ArrayList<>(keys.size());
        keys.forEach(key -> {
            projectIds.add(key.projectId());
            fingerprints.add(key.fingerprint());
        });
        // 지문 수만큼 조건을 늘어놓지 않고 배열 두 개를 짝지어 한 번에 찾는다.
        jdbc.sql("""
                        SELECT f.project_id, f.fingerprint, f.issue_id
                        FROM issue_fingerprints f
                        JOIN unnest(?::bigint[], ?::text[]) AS k(project_id, fingerprint)
                          ON f.project_id = k.project_id AND f.fingerprint = k.fingerprint
                        """)
                .params(projectIds.toArray(Long[]::new), fingerprints.toArray(String[]::new))
                .query(rs -> {
                    found.put(new IssueKey(rs.getLong(1), rs.getString(2).strip()), rs.getLong(3));
                });
        return found;
    }

    /**
     * 이슈를 만들고 지문 행을 {@code ON CONFLICT DO NOTHING} 으로 넣는다. 넣지 못했으면 다른 worker 가 먼저 만든 것이므로
     * 만든 이슈를 거두고(트랜잭션 되돌림) 먼저 만든 이슈를 쓴다. 같은 지문을 동시에 넣으면 유일 색인이 뒤의 것을 앞의 것이
     * 끝날 때까지 기다리게 하므로 이슈는 하나만 남는다(6.5).
     */
    @Override
    public long createIssue(NewIssue issue) {
        Optional<Long> created = transactions.execute(status -> {
            long issueId = jdbc.sql("""
                            INSERT INTO issues (project_id, title, culprit, first_seen, last_seen, last_release, first_release)
                            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                            """)
                    .params(issue.key().projectId(), issue.title(), issue.culprit(), Timestamp.from(issue.firstSeen()),
                            Timestamp.from(issue.lastSeen()), emptyToNull(issue.release()), emptyToNull(issue.release()))
                    .query(Long.class)
                    .single();
            int linked = jdbc.sql("""
                            INSERT INTO issue_fingerprints (project_id, fingerprint, issue_id, rule_version)
                            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                            """)
                    .params(issue.key().projectId(), issue.key().fingerprint(), issueId, issue.ruleVersion())
                    .update();
            if (linked == 0) {
                status.setRollbackOnly();
                return Optional.empty();
            }
            return Optional.of(issueId);
        });
        if (created != null && created.isPresent()) {
            return created.get();
        }
        return jdbc.sql("SELECT issue_id FROM issue_fingerprints WHERE project_id = ? AND fingerprint = ?")
                .params(issue.key().projectId(), issue.key().fingerprint())
                .query(Long.class)
                .single();
    }

    /**
     * 이슈마다 최근 발생을 고치고 상태를 바꾼다(6.6).
     *
     * <ul>
     *   <li>해결 → 재발: 해결한 릴리스보다 나중 릴리스에서 났을 때. 순서는 릴리스의 등록 시각(releases.created_at)으로 본다.
     *       브라우저는 새로고침 전까지 옛 번들을 실행하므로 이전 릴리스에서 온 것은 재발로 치지 않는다.</li>
     *   <li>무시 → 미해결: 기한이 지났거나 무시한 뒤의 발생 횟수가 조건에 닿았을 때.</li>
     * </ul>
     * 다른 worker 가 더 나중 시각으로 이미 고쳤으면 최근 발생과 최근 릴리스를 되돌리지 않는다.
     */
    @Override
    public void touch(List<IssueSeen> seen) {
        if (seen.isEmpty()) {
            return;
        }
        template.batchUpdate("""
                        UPDATE issues i SET
                          last_release = CASE WHEN ? > i.last_seen THEN COALESCE(?, i.last_release) ELSE i.last_release END,
                          last_seen = GREATEST(i.last_seen, ?),
                          status = CASE
                            WHEN i.status = 'resolved' AND (
                                   i.resolved_in_release IS NULL OR ?::text IS NULL
                                   OR (EXISTS (SELECT 1 FROM releases e WHERE e.project_id = i.project_id AND e.version = ?)
                                       AND EXISTS (SELECT 1 FROM releases r WHERE r.project_id = i.project_id AND r.version = i.resolved_in_release)
                                       AND (SELECT e.created_at FROM releases e WHERE e.project_id = i.project_id AND e.version = ?)
                                         > (SELECT r.created_at FROM releases r WHERE r.project_id = i.project_id AND r.version = i.resolved_in_release))
                                   OR (NOT EXISTS (SELECT 1 FROM releases e WHERE e.project_id = i.project_id AND e.version = ?)
                                       AND ? <> i.resolved_in_release))
                              THEN 'regressed'
                            WHEN i.status = 'ignored' AND (
                                   (i.ignored_until IS NOT NULL AND i.ignored_until <= now())
                                   OR (i.ignored_until_count IS NOT NULL AND i.ignored_count + ? >= i.ignored_until_count))
                              THEN 'unresolved'
                            ELSE i.status
                          END,
                          ignored_count = CASE WHEN i.status = 'ignored' THEN i.ignored_count + ? ELSE i.ignored_count END,
                          updated_at = now()
                        WHERE i.id = ?
                        """,
                seen, seen.size(), (ps, s) -> {
                    Timestamp lastSeen = Timestamp.from(s.lastSeen());
                    String release = emptyToNull(s.release());
                    ps.setTimestamp(1, lastSeen);
                    ps.setString(2, release);
                    ps.setTimestamp(3, lastSeen);
                    ps.setString(4, release);
                    ps.setString(5, release);
                    ps.setString(6, release);
                    ps.setString(7, release);
                    ps.setString(8, release);
                    ps.setLong(9, s.events());
                    ps.setLong(10, s.events());
                    ps.setLong(11, s.issueId());
                });
        // 예전 릴리스의 이벤트가 늦게 와 최초 발생보다 이른 경우도 있다(브라우저의 옛 번들, 6.6).
        template.batchUpdate("UPDATE issues SET first_seen = ? WHERE id = ? AND first_seen > ?",
                seen, seen.size(), (ps, s) -> {
                    ps.setTimestamp(1, Timestamp.from(s.lastSeen()));
                    ps.setLong(2, s.issueId());
                    ps.setTimestamp(3, Timestamp.from(s.lastSeen()));
                });
    }

    @Override
    public void quarantine(List<Quarantine> items) {
        if (items.isEmpty()) {
            return;
        }
        template.batchUpdate("INSERT INTO quarantined_events (project_id, raw, reason) VALUES (?, ?, ?)",
                items, items.size(), (ps, q) -> {
                    ps.setLong(1, q.projectId());
                    ps.setBytes(2, q.raw());
                    ps.setString(3, q.reason());
                });
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
