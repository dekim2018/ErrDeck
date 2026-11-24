package com.errdeck.api.app;

import com.errdeck.api.app.query.ScopedQuery;
import com.errdeck.api.domain.Role;
import com.errdeck.api.out.clickhouse.ClickHouseReader;
import com.errdeck.core.net.UrlGuard;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 업타임 모니터(9장)와 알림 규칙(16장)의 관리. 주소는 만들 때 내부 주소를 거르고(9.4), worker 가 부르기 직전에 한 번 더 거른다.
 *
 * <p>마감 때문에 저장소 포트 없이 JdbcClient 를 바로 쓴다(CHOICES 20-1). 리팩토링 때 다른 서비스처럼 나눈다.
 */
@Service
public class OpsService {

    public record MonitorView(long id, long projectId, String name, String url, String method, int intervalSeconds, int timeoutMs,
                              int expectedStatusMin, int expectedStatusMax, String expectedBody, String status, Instant pausedUntil,
                              Instant lastCheckedAt, String lastError, Instant certExpiresAt, Double uptime24h, Double uptime30d,
                              Instant openIncidentSince) {
    }

    public record NewMonitor(String name, String url, String method, Integer intervalSeconds, Integer timeoutMs, Integer expectedStatusMin,
                             Integer expectedStatusMax, String expectedBody) {
    }

    /** @param grafanaUrl 장애 시작 15분 전부터 끝난 뒤 15분까지의 JVM 대시보드(12.4 셋째 줄) */
    public record IncidentView(long id, Instant startedAt, Instant endedAt, String cause, String grafanaUrl) {
    }

    public record RuleView(long id, long projectId, String kind, Long threshold, Integer windowMinutes, String channel, String target,
                           int cooldownMinutes, boolean enabled) {
    }

    public record NewRule(String kind, Long threshold, Integer windowMinutes, String channel, String target, Integer cooldownMinutes) {
    }

    public record NotificationView(long id, long ruleId, String kind, String title, String status, int attempts, String lastError,
                                   Instant createdAt, Instant sentAt) {
    }

    private static final Set<String> KINDS = Set.of("new_issue", "regression", "frequency", "monitor_down", "cert_expiry", "resource");
    private static final Set<String> CHANNELS = Set.of("slack", "discord");

    private final JdbcClient jdbc;
    private final ClickHouseReader clickHouse;
    private final AccessPolicy access;
    private final UrlGuard guard;
    private final Clock clock;

    private final String grafanaUrl;

    public OpsService(JdbcClient jdbc, ClickHouseReader clickHouse, AccessPolicy access, UrlGuard guard, Clock clock,
                      com.errdeck.api.config.ConsoleProperties console) {
        this.grafanaUrl = console.grafanaUrl() == null ? null : console.grafanaUrl().toString().replaceAll("/$", "");
        this.jdbc = jdbc;
        this.clickHouse = clickHouse;
        this.access = access;
        this.guard = guard;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 모니터

    public List<MonitorView> monitors(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.VIEWER);
        Map<Long, double[]> uptime = uptime(projectId);
        return jdbc.sql("""
                        SELECT m.*, (SELECT started_at FROM monitor_incidents i WHERE i.monitor_id = m.id AND i.ended_at IS NULL) AS open_since
                        FROM monitors m WHERE m.project_id = ? ORDER BY m.id
                        """)
                .param(projectId)
                .query((rs, n) -> {
                    double[] u = uptime.get(rs.getLong("id"));
                    return new MonitorView(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"), rs.getString("url"),
                            rs.getString("method"), rs.getInt("interval_seconds"), rs.getInt("timeout_ms"), rs.getInt("expected_status_min"),
                            rs.getInt("expected_status_max"), rs.getString("expected_body"), rs.getString("status"),
                            instant(rs.getTimestamp("paused_until")), instant(rs.getTimestamp("last_checked_at")), rs.getString("last_error"),
                            instant(rs.getTimestamp("cert_expires_at")), u == null ? null : u[0], u == null ? null : u[1],
                            instant(rs.getTimestamp("open_since")));
                })
                .list();
    }

    public long createMonitor(long userId, long projectId, NewMonitor m) {
        access.requireProject(userId, projectId, Role.MEMBER);
        String url = checkUrl(m.url());
        int interval = m.intervalSeconds() == null ? 60 : m.intervalSeconds();
        if (interval != 30 && interval != 60 && interval != 300) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "주기는 30, 60, 300초 중 하나다");
        }
        String method = m.method() == null ? "GET" : m.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "메서드는 GET 이나 HEAD 다");
        }
        int min = m.expectedStatusMin() == null ? 200 : m.expectedStatusMin();
        int max = m.expectedStatusMax() == null ? 299 : m.expectedStatusMax();
        if (min < 100 || max > 599 || min > max) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "상태 코드 범위가 틀렸다");
        }
        int timeout = m.timeoutMs() == null ? 5000 : Math.max(500, Math.min(10_000, m.timeoutMs()));
        return jdbc.sql("""
                        INSERT INTO monitors (project_id, name, url, method, interval_seconds, timeout_ms, expected_status_min,
                                              expected_status_max, expected_body)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(projectId, blank(m.name()), url, method, interval, timeout, min, max, blank(m.expectedBody()))
                .query(Long.class)
                .single();
    }

    /** 일시 중지(9.1). 기한이 없으면 다시 켤 때까지. null 이면 다시 켠다. */
    public void pause(long userId, long monitorId, Instant until, boolean resume) {
        long projectId = monitorProject(monitorId);
        access.requireProject(userId, projectId, Role.MEMBER);
        Instant pausedUntil = resume ? null : until != null ? until : Instant.parse("9999-12-31T00:00:00Z");
        jdbc.sql("UPDATE monitors SET paused_until = ? WHERE id = ?")
                .params(pausedUntil == null ? null : Timestamp.from(pausedUntil), monitorId)
                .update();
    }

    public void deleteMonitor(long userId, long monitorId) {
        long projectId = monitorProject(monitorId);
        access.requireProject(userId, projectId, Role.MEMBER);
        jdbc.sql("DELETE FROM monitor_incidents WHERE monitor_id = ?").param(monitorId).update();
        jdbc.sql("DELETE FROM monitors WHERE id = ?").param(monitorId).update();
    }

    public List<IncidentView> incidents(long userId, long monitorId) {
        long projectId = monitorProject(monitorId);
        access.requireProject(userId, projectId, Role.VIEWER);
        String slug = jdbc.sql("SELECT slug FROM projects WHERE id = ?").param(projectId).query(String.class).single();
        return jdbc.sql("SELECT id, started_at, ended_at, cause FROM monitor_incidents WHERE monitor_id = ? ORDER BY started_at DESC LIMIT 50")
                .param(monitorId)
                .query((rs, n) -> {
                    Instant start = rs.getTimestamp(2).toInstant();
                    Instant end = instant(rs.getTimestamp(3));
                    String link = grafanaUrl == null ? null : grafanaUrl + "/d/errdeck-jvm?var-job="
                            + java.net.URLEncoder.encode(slug, java.nio.charset.StandardCharsets.UTF_8)
                            + "&from=" + start.minus(Duration.ofMinutes(15)).toEpochMilli()
                            + "&to=" + (end == null ? clock.instant() : end).plus(Duration.ofMinutes(15)).toEpochMilli();
                    return new IncidentView(rs.getLong(1), start, end, rs.getString(4), link);
                })
                .list();
    }

    /** 가동률은 점검한 것 중 성공한 비율이다. 중지한 동안은 점검하지 않아 계산에서 빠진다(9.1). */
    private Map<Long, double[]> uptime(long projectId) {
        Map<Long, double[]> out = new HashMap<>();
        Instant now = clock.instant();
        try {
            for (Map<String, Object> row : clickHouse.query(ScopedQuery.from("uptime_checks", Set.of(projectId), "timestamp",
                            now.minus(Duration.ofDays(30)).plusSeconds(60), now.plusSeconds(60))
                    .select("monitor_id", "avgIf(ok, timestamp >= now() - INTERVAL 1 DAY) AS d1", "avg(ok) AS d30")
                    .groupBy("monitor_id"))) {
                out.put(Long.parseLong(String.valueOf(row.get("monitor_id"))),
                        new double[] {number(row.get("d1")), number(row.get("d30"))});
            }
        } catch (RuntimeException e) {
            // 가동률이 없어도 모니터 목록은 보인다. 조회 기간 상한(30일, 10.5)에 걸리지 않게 위에서 1분 줄였다.
            org.slf4j.LoggerFactory.getLogger(OpsService.class).warn("가동률을 읽지 못했다: {}", e.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ 알림 규칙

    public List<RuleView> rules(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.VIEWER);
        return jdbc.sql("""
                        SELECT id, project_id, kind, threshold, window_minutes, channel, target, cooldown_minutes, enabled
                        FROM alert_rules WHERE project_id = ? ORDER BY id
                        """)
                .param(projectId)
                .query((rs, n) -> new RuleView(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4), (Integer) rs.getObject(5),
                        rs.getString(6), mask(rs.getString(7)), rs.getInt(8), rs.getBoolean(9)))
                .list();
    }

    public long createRule(long userId, long projectId, NewRule r) {
        access.requireProject(userId, projectId, Role.ADMIN);
        if (r.kind() == null || !KINDS.contains(r.kind())) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "규칙 종류는 " + KINDS + " 중 하나다");
        }
        if (r.channel() == null || !CHANNELS.contains(r.channel())) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "채널은 slack 이나 discord 다(이메일은 아직 없다)");
        }
        if ("frequency".equals(r.kind()) && (r.threshold() == null || r.threshold() < 1 || r.windowMinutes() == null
                || r.windowMinutes() < 1 || r.windowMinutes() > 1440)) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "빈도 규칙은 기준 건수와 구간(1~1440분)이 있어야 한다");
        }
        String target = checkUrl(r.target());
        int cooldown = r.cooldownMinutes() == null ? 30 : Math.max(1, Math.min(1440, r.cooldownMinutes()));
        return jdbc.sql("""
                        INSERT INTO alert_rules (project_id, kind, threshold, window_minutes, channel, target, cooldown_minutes)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(projectId, r.kind(), r.threshold(), r.windowMinutes(), r.channel(), target, cooldown)
                .query(Long.class)
                .single();
    }

    public void setRuleEnabled(long userId, long ruleId, boolean enabled) {
        access.requireProject(userId, ruleProject(ruleId), Role.ADMIN);
        jdbc.sql("UPDATE alert_rules SET enabled = ? WHERE id = ?").params(enabled, ruleId).update();
    }

    public List<NotificationView> notifications(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.VIEWER);
        return jdbc.sql("""
                        SELECT n.id, n.rule_id, n.kind, n.title, n.status, n.attempts, n.last_error, n.created_at, n.sent_at
                        FROM notifications n JOIN alert_rules r ON r.id = n.rule_id
                        WHERE r.project_id = ? ORDER BY n.created_at DESC LIMIT 50
                        """)
                .param(projectId)
                .query((rs, n) -> new NotificationView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7), rs.getTimestamp(8).toInstant(), instant(rs.getTimestamp(9))))
                .list();
    }

    /**
     * Alertmanager 가 넘긴 자원 경보(13.6)를 'resource' 규칙마다 알림으로 연다. 발송은 worker 가 한다(16.3).
     * 같은 경보의 같은 상태는 한 번만 간다.
     */
    public int alertmanager(List<Map<String, Object>> alerts, org.springframework.data.redis.core.StringRedisTemplate redis) {
        List<long[]> rules = jdbc.sql("SELECT id, project_id FROM alert_rules WHERE kind = 'resource' AND enabled")
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .list();
        int opened = 0;
        for (Map<String, Object> alert : alerts) {
            Object labels = alert.get("labels");
            Object annotations = alert.get("annotations");
            String name = labels instanceof Map<?, ?> l ? String.valueOf(l.get("alertname")) : "경보";
            String summary = annotations instanceof Map<?, ?> a && a.get("summary") != null ? String.valueOf(a.get("summary")) : "";
            String status = String.valueOf(alert.get("status"));
            String key = "am:" + alert.get("fingerprint") + ":" + status + ":" + alert.get("startsAt");
            String title = ("resolved".equals(status) ? "해소: " : "경보: ") + name;
            for (long[] rule : rules) {
                var id = jdbc.sql("""
                                INSERT INTO notifications (rule_id, dedupe_key, kind, title, body) VALUES (?, ?, 'resource', ?, ?)
                                ON CONFLICT (rule_id, dedupe_key) DO NOTHING RETURNING id
                                """)
                        .params(rule[0], key, title, summary)
                        .query(Long.class)
                        .optional();
                if (id.isPresent()) {
                    redis.opsForStream().add(org.springframework.data.redis.connection.stream.StreamRecords
                            .string(Map.of("id", Long.toString(id.get()))).withStreamKey("errdeck:stream:notify"));
                    opened++;
                }
            }
        }
        return opened;
    }

    // ------------------------------------------------------------------ 도움

    private String checkUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 2000) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "주소가 없다");
        }
        try {
            return guard.check(url).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, e.getMessage());
        }
    }

    private long monitorProject(long monitorId) {
        return jdbc.sql("SELECT project_id FROM monitors WHERE id = ?").param(monitorId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(ApiException.Kind.NOT_FOUND, "모니터가 없다"));
    }

    private long ruleProject(long ruleId) {
        return jdbc.sql("SELECT project_id FROM alert_rules WHERE id = ?").param(ruleId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(ApiException.Kind.NOT_FOUND, "규칙이 없다"));
    }

    /** 웹훅 주소는 그 자체가 비밀이다. 목록에는 앞부분만 보인다. */
    private static String mask(String target) {
        int cut = Math.min(target.length(), target.indexOf('/', 8) < 0 ? target.length() : target.indexOf('/', 8) + 1);
        return target.substring(0, cut) + "…";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
}
