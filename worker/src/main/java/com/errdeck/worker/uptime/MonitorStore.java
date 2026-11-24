package com.errdeck.worker.uptime;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 모니터의 판정 상태(9.3). 상태를 바꾸는 UPDATE 는 "지금 상태가 아닐 때만" 조건을 달아, worker 가 여러 대여도
 * 바뀌는 순간을 한 곳만 본다. 그곳만 장애 기록을 열고 닫고 알린다.
 */
public class MonitorStore {

    /** 이 점검으로 일어난 일. */
    public enum Transition { NONE, WENT_DOWN, RECOVERED, RETRY }

    public record Outcome(Transition transition, Monitor monitor, String status) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public MonitorStore(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public List<Monitor> all() {
        return jdbc.sql("""
                        SELECT id, project_id, name, url, method, interval_seconds, timeout_ms, expected_status_min, expected_status_max,
                               expected_body, paused_until
                        FROM monitors
                        """)
                .query((rs, n) -> new Monitor(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"), rs.getString("url"),
                        rs.getString("method"), rs.getInt("interval_seconds"), rs.getInt("timeout_ms"), rs.getInt("expected_status_min"),
                        rs.getInt("expected_status_max"), rs.getString("expected_body"),
                        rs.getTimestamp("paused_until") == null ? null : rs.getTimestamp("paused_until").toInstant()))
                .list();
    }

    public Outcome apply(Monitor monitor, CheckResult result) {
        return transactions.execute(tx -> {
            var row = jdbc.sql("""
                            UPDATE monitors SET
                              consecutive_failures = CASE WHEN ? THEN 0 ELSE consecutive_failures + 1 END,
                              consecutive_successes = CASE WHEN ? THEN consecutive_successes + 1 ELSE 0 END,
                              last_checked_at = ?, last_error = ?, cert_expires_at = COALESCE(?, cert_expires_at)
                            WHERE id = ?
                            RETURNING status, consecutive_failures, consecutive_successes
                            """)
                    .params(result.ok(), result.ok(), Timestamp.from(result.timestamp()), result.error(),
                            result.certExpiresAt() == null ? null : Timestamp.from(result.certExpiresAt()), monitor.id())
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getInt(3)})
                    .optional();
            if (row.isEmpty()) {
                return new Outcome(Transition.NONE, monitor, "unknown");
            }
            String status = (String) row.get()[0];
            int failures = (int) row.get()[1];
            int successes = (int) row.get()[2];
            if (!result.ok()) {
                if (failures >= 2 && !"down".equals(status) && changeStatus(monitor.id(), "down")) {
                    jdbc.sql("""
                                    INSERT INTO monitor_incidents (monitor_id, started_at, cause) VALUES (?, ?, ?)
                                    ON CONFLICT (monitor_id) WHERE ended_at IS NULL DO NOTHING
                                    """)
                            .params(monitor.id(), Timestamp.from(result.timestamp()), result.error())
                            .update();
                    return new Outcome(Transition.WENT_DOWN, monitor, "down");
                }
                return new Outcome(failures == 1 && !"down".equals(status) ? Transition.RETRY : Transition.NONE, monitor, status);
            }
            // 처음 점검이면 한 번의 성공으로 정상이다. 다운에서는 연속 2회 성공해야 복구다.
            int needed = "down".equals(status) ? 2 : 1;
            if (successes >= needed && !"up".equals(status) && changeStatus(monitor.id(), "up")) {
                boolean wasDown = "down".equals(status);
                if (wasDown) {
                    jdbc.sql("UPDATE monitor_incidents SET ended_at = ? WHERE monitor_id = ? AND ended_at IS NULL")
                            .params(Timestamp.from(result.timestamp()), monitor.id())
                            .update();
                }
                return new Outcome(wasDown ? Transition.RECOVERED : Transition.NONE, monitor, "up");
            }
            return new Outcome("down".equals(status) && successes == 1 ? Transition.RETRY : Transition.NONE, monitor, status);
        });
    }

    /** 다운인 모니터 수와 점검 중인(중지되지 않은) 모니터 수. 점검기 자신의 문제를 가린다(9.5). */
    public int[] downAndActive(Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'down'), count(*)
                        FROM monitors WHERE paused_until IS NULL OR paused_until <= ?
                        """)
                .param(Timestamp.from(now))
                .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)})
                .single();
    }

    private boolean changeStatus(long id, String status) {
        return jdbc.sql("UPDATE monitors SET status = ? WHERE id = ? AND status <> ?").params(status, id, status).update() > 0;
    }
}
