package com.errdeck.worker.alert;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 알림 판정(16.1, 16.2). 판정한 곳이 규칙마다 {@code notifications} 에 (규칙, 중복 방지 키)를 넣고, 넣는 데 성공한 worker 만
 * 발송 대기열({@code errdeck:stream:notify})에 넣는다. 재알림 간격과 worker 여러 대의 중복이 유일 제약 하나로 함께 막힌다.
 *
 * <p>새 이슈와 재발은 PostgreSQL 을 10초마다 본다. 쓰기 단계에 끼워 넣지 않아 이벤트 처리가 알림 때문에 늦어지지 않는다.
 * 새 이슈는 끝난 1분 단위로 모아 프로젝트마다 한 메시지로 보낸다(16.2 둘째 줄).
 */
public class AlertEngine {

    public static final String STREAM = "errdeck:stream:notify";
    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    private static final int MAX_TITLES = 10;

    public record Rule(long id, long projectId, String kind, Long threshold, Integer windowMinutes, int cooldownMinutes) {
    }

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final FrequencySource frequency;
    private final Clock clock;
    private final String consoleUrl;
    private Instant newIssuesUntil;
    private Instant regressedUntil;

    public AlertEngine(JdbcClient jdbc, StringRedisTemplate redis, FrequencySource frequency, Clock clock, String consoleUrl) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.frequency = frequency;
        this.clock = clock;
        this.consoleUrl = consoleUrl.replaceAll("/+$", "");
        Instant now = clock.instant();
        this.newIssuesUntil = now.truncatedTo(ChronoUnit.MINUTES);
        this.regressedUntil = now;
    }

    /** 규칙 종류에 맞는 규칙마다 알림을 하나 연다. 같은 키로 이미 연 것이면 아무것도 하지 않는다. */
    public int fire(long projectId, String kind, String key, String title, String body, String path) {
        int fired = 0;
        for (Rule rule : rules(projectId, kind)) {
            if (open(rule, key, kind, title, body, path)) {
                fired++;
            }
        }
        return fired;
    }

    /** 재알림 간격으로 나눈 구간 번호. 같은 구간에서는 키가 같아 한 번만 간다(16.2). */
    public long bucket(Rule rule) {
        return clock.instant().getEpochSecond() / (rule.cooldownMinutes() * 60L);
    }

    public List<Rule> rules(long projectId, String kind) {
        return jdbc.sql("""
                        SELECT id, project_id, kind, threshold, window_minutes, cooldown_minutes FROM alert_rules
                        WHERE project_id = ? AND kind = ? AND enabled
                        """)
                .params(projectId, kind)
                .query((rs, n) -> new Rule(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4),
                        (Integer) rs.getObject(5), rs.getInt(6)))
                .list();
    }

    /**
     * 콘솔 주소. 콘솔의 경로는 시스템 아래에 있으므로 프로젝트의 시스템을 찾아 붙인다.
     * {@code /issues/1} 은 {@code /systems/{s}/issues/1}, {@code /projects/2/monitors} 는 {@code /systems/{s}/projects/2/monitors} 가 된다.
     */
    private String link(long projectId, String path) {
        if (path == null) {
            return null;
        }
        long system = jdbc.sql("SELECT system_id FROM projects WHERE id = ?").param(projectId).query(Long.class).optional().orElse(0L);
        return consoleUrl + "/systems/" + system + path;
    }

    boolean open(Rule rule, String key, String kind, String title, String body, String path) {
        var id = jdbc.sql("""
                        INSERT INTO notifications (rule_id, dedupe_key, kind, title, body, link) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (rule_id, dedupe_key) DO NOTHING RETURNING id
                        """)
                .params(rule.id(), key, kind, title, body, link(rule.projectId(), path))
                .query(Long.class)
                .optional();
        if (id.isEmpty()) {
            return false;
        }
        redis.opsForStream().add(StreamRecords.string(Map.of("id", Long.toString(id.get()))).withStreamKey(STREAM));
        return true;
    }

    /** 새 이슈와 재발을 본다. 스케줄러가 10초마다 부른다. */
    public void pollIssues() {
        try {
            Instant now = clock.instant();
            // 새 이슈는 다 지난 분까지만 본다. 그 분의 새 이슈를 한 메시지에 모으기 위해서다.
            Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
            if (minute.isAfter(newIssuesUntil)) {
                newIssues(newIssuesUntil, minute);
                newIssuesUntil = minute;
            }
            regressed(regressedUntil, now);
            regressedUntil = now;
        } catch (RuntimeException e) {
            log.warn("새 이슈와 재발을 보지 못했다: {}", e.toString());
        }
    }

    private void newIssues(Instant from, Instant to) {
        record Row(long id, long projectId, Instant createdAt, String title, String culprit) {
        }
        List<Row> rows = jdbc.sql("SELECT id, project_id, created_at, title, culprit FROM issues WHERE created_at >= ? AND created_at < ? ORDER BY id")
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5)))
                .list();
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(row.projectId() + ":" + row.createdAt().truncatedTo(ChronoUnit.MINUTES).getEpochSecond(),
                    k -> new ArrayList<>()).add(row);
        }
        groups.forEach((group, issues) -> {
            long projectId = issues.getFirst().projectId();
            Row first = issues.getFirst();
            String title = issues.size() == 1 ? "새 이슈: " + first.title() : "새 이슈 " + issues.size() + "개";
            StringBuilder body = new StringBuilder();
            issues.stream().limit(MAX_TITLES).forEach(i -> body.append("• ").append(i.title())
                    .append(i.culprit() == null ? "" : " (" + i.culprit() + ")").append('\n'));
            if (issues.size() > MAX_TITLES) {
                body.append("… 외 ").append(issues.size() - MAX_TITLES).append("개\n");
            }
            String path = issues.size() == 1 ? "/issues/" + first.id() : "/projects/" + projectId + "/issues";
            fire(projectId, "new_issue", "new:" + group, title, body.toString().strip(), path);
        });
    }

    private void regressed(Instant from, Instant to) {
        record Row(long id, long projectId, String title, String release, Instant at) {
        }
        // 행을 다 읽은 뒤에 알린다. 결과를 읽는 동안 다른 커넥션을 빌리지 않는다(풀 4개, 14.6).
        jdbc.sql("SELECT id, project_id, title, last_release, regressed_at FROM issues WHERE regressed_at > ? AND regressed_at <= ?")
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()))
                .list()
                .forEach(r -> fire(r.projectId(), "regression", "regressed:" + r.id() + ":" + r.at().getEpochSecond(), "재발: " + r.title(),
                        "릴리스 " + r.release() + " 에서 다시 났다", "/issues/" + r.id()));
    }

    /** 빈도 초과(16.1): 규칙의 구간 동안 한 이슈가 기준 건수 이상이면. 스케줄러가 30초마다 부른다. */
    public void pollFrequency() {
        try {
            List<Rule> all = jdbc.sql("""
                            SELECT id, project_id, kind, threshold, window_minutes, cooldown_minutes FROM alert_rules
                            WHERE kind = 'frequency' AND enabled AND threshold IS NOT NULL AND window_minutes IS NOT NULL
                            """)
                    .query((rs, n) -> new Rule(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getInt(5), rs.getInt(6)))
                    .list();
            for (Rule rule : all) {
                Map<String, Long> hot = frequency.over(rule.projectId(), Duration.ofMinutes(rule.windowMinutes()), rule.threshold());
                if (hot.isEmpty()) {
                    continue;
                }
                record Hit(long issueId, String title, String fingerprint) {
                }
                jdbc.sql("""
                                SELECT i.id, i.title, f.fingerprint FROM issue_fingerprints f JOIN issues i ON i.id = f.issue_id
                                WHERE i.project_id = ? AND f.fingerprint IN (:fps) AND i.status <> 'ignored'
                                """.replace("(:fps)", "(" + String.join(",", java.util.Collections.nCopies(hot.size(), "?")) + ")"))
                        .params(concat(rule.projectId(), hot.keySet()))
                        .query((rs, n) -> new Hit(rs.getLong(1), rs.getString(2), rs.getString(3)))
                        .list()
                        .forEach(h -> open(rule, "freq:" + h.issueId() + ":" + bucket(rule), "frequency", "빈도 초과: " + h.title(),
                                rule.windowMinutes() + "분 동안 " + hot.get(h.fingerprint()) + "건(기준 " + rule.threshold() + "건)",
                                "/issues/" + h.issueId()));
            }
        } catch (RuntimeException e) {
            log.warn("빈도 규칙을 보지 못했다: {}", e.toString());
        }
    }

    private static List<Object> concat(Object first, java.util.Collection<String> rest) {
        List<Object> out = new ArrayList<>(rest.size() + 1);
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /** 이슈별 최근 건수. ClickHouse 의 분 단위 집계에서 읽는다. */
    public interface FrequencySource {
        /** @return 기준 이상인 지문과 건수 */
        Map<String, Long> over(long projectId, Duration window, long threshold);
    }
}
