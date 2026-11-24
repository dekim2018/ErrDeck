package com.errdeck.api.app;

import com.errdeck.api.out.prometheus.PrometheusEndpointMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 통합 화면(12장). 시스템마다 상태 신호 3단계와, 스프링 프로젝트마다 요약 카드 지표 6개(12.2)를 낸다. 카드는 프로메테우스의
 * 즉시 값이고 15초 동안 캐시한다. 프로메테우스가 2초 안에 답하지 않으면 카드만 비우고 화면은 뜬다(14.9, 22 작업의 끝 기준).
 */
@Service
public class OverviewService {

    public enum Signal { OK, WARN, DOWN }

    /** 요약 카드(12.2). 값이 없으면 null. */
    public record Card(Double up, Double cpu, Double heap, Double p95Ms, Double errorRate, Double poolPending) {
    }

    public record ProjectOverview(long id, String slug, String platform, Signal signal, long openIssues, long regressed, long newIssues24h,
                                  long monitorsDown, long monitors, Card card, List<String> reasons) {
    }

    public record SystemOverview(long id, String name, String role, Signal signal, List<ProjectOverview> projects) {
    }

    public record Overview(List<SystemOverview> systems, boolean metricsAvailable, String grafanaUrl) {
    }

    private static final Duration CACHE = Duration.ofSeconds(15);
    private static final Map<String, String> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("up", "max by (job) (up)");
        QUERIES.put("cpu", "max by (job) (process_cpu_usage)");
        QUERIES.put("heap", "sum by (job) (jvm_memory_used_bytes{area=\"heap\"}) / sum by (job) (jvm_memory_max_bytes{area=\"heap\"} > 0)");
        QUERIES.put("p95", "histogram_quantile(0.95, sum by (job, le) (rate(http_server_requests_seconds_bucket{uri!~\"/actuator.*\"}[5m])))");
        QUERIES.put("errors", "sum by (job) (rate(http_server_requests_seconds_count{status=~\"5..\"}[5m])) "
                + "/ sum by (job) (rate(http_server_requests_seconds_count[5m]))");
        QUERIES.put("pool", "max by (job) (hikaricp_connections_pending)");
    }

    private final JdbcClient jdbc;
    private final PrometheusEndpointMetrics prometheus;
    private final WorkspaceRepository workspace;
    private final Clock clock;
    private final String grafanaUrl;
    private volatile Instant cachedAt = Instant.EPOCH;
    private volatile Map<String, Map<String, Double>> cached;

    public OverviewService(JdbcClient jdbc, PrometheusEndpointMetrics prometheus, WorkspaceRepository workspace, Clock clock,
                           com.errdeck.api.config.ConsoleProperties console) {
        this.jdbc = jdbc;
        this.prometheus = prometheus;
        this.workspace = workspace;
        this.clock = clock;
        this.grafanaUrl = console.grafanaUrl() == null ? null : console.grafanaUrl().toString();
    }

    public Overview overview(long userId) {
        Map<String, Map<String, Double>> metrics = metrics();
        List<SystemOverview> systems = new ArrayList<>();
        for (WorkspaceRepository.SystemView system : workspace.systemsOf(userId)) {
            List<ProjectOverview> projects = new ArrayList<>();
            for (WorkspaceRepository.Project p : workspace.projects(system.id())) {
                projects.add(project(p, metrics));
            }
            Signal worst = projects.stream().map(ProjectOverview::signal).max(Enum::compareTo).orElse(Signal.OK);
            systems.add(new SystemOverview(system.id(), system.name(), system.role().name(), worst, projects));
        }
        return new Overview(systems, metrics != null, grafanaUrl);
    }

    private ProjectOverview project(WorkspaceRepository.Project p, Map<String, Map<String, Double>> metrics) {
        Instant now = clock.instant();
        long[] issues = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status IN ('unresolved', 'regressed')),
                               count(*) FILTER (WHERE status = 'regressed'),
                               count(*) FILTER (WHERE created_at >= ?),
                               count(*) FILTER (WHERE created_at >= ?)
                        FROM issues WHERE project_id = ?
                        """)
                .params(java.sql.Timestamp.from(now.minus(Duration.ofDays(1))), java.sql.Timestamp.from(now.minus(Duration.ofHours(1))), p.id())
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)})
                .single();
        long[] monitors = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'down' AND (paused_until IS NULL OR paused_until <= now())), count(*)
                        FROM monitors WHERE project_id = ?
                        """)
                .param(p.id())
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        Card card = metrics == null ? null : card(metrics, p.slug());
        List<String> reasons = new ArrayList<>();
        Signal signal = Signal.OK;
        if (monitors[0] > 0) {
            reasons.add("모니터 " + monitors[0] + "개 다운");
            signal = Signal.DOWN;
        }
        if (card != null && card.up() != null && card.up() == 0) {
            reasons.add("프로세스가 응답하지 않는다");
            signal = Signal.DOWN;
        }
        if (signal != Signal.DOWN) {
            if (issues[1] > 0) {
                reasons.add("재발 " + issues[1] + "건");
            }
            if (issues[3] > 0) {
                reasons.add("1시간 안의 새 이슈 " + issues[3] + "건");
            }
            if (card != null) {
                if (card.cpu() != null && card.cpu() > 0.8) {
                    reasons.add("CPU " + Math.round(card.cpu() * 100) + "%");
                }
                if (card.heap() != null && card.heap() > 0.85) {
                    reasons.add("힙 " + Math.round(card.heap() * 100) + "%");
                }
                if (card.errorRate() != null && card.errorRate() > 0.01) {
                    reasons.add("5xx " + String.format("%.1f", card.errorRate() * 100) + "%");
                }
                if (card.poolPending() != null && card.poolPending() > 0) {
                    reasons.add("커넥션 풀 대기");
                }
            }
            if (!reasons.isEmpty()) {
                signal = Signal.WARN;
            }
        }
        return new ProjectOverview(p.id(), p.slug(), p.platform().dbValue(), signal, issues[0], issues[1], issues[2], monitors[0], monitors[1],
                card, reasons);
    }

    private static Card card(Map<String, Map<String, Double>> m, String job) {
        if (!m.get("up").containsKey(job)) {
            return null;
        }
        Double p95 = m.get("p95").get(job);
        return new Card(m.get("up").get(job), m.get("cpu").get(job), m.get("heap").get(job),
                p95 == null || p95.isNaN() ? null : p95 * 1000, nan(m.get("errors").get(job)), m.get("pool").get(job));
    }

    private static Double nan(Double v) {
        return v == null || v.isNaN() ? null : v;
    }

    /** 질의 6개를 job 별로. 못 읽으면 null 이다. */
    private Map<String, Map<String, Double>> metrics() {
        Instant now = clock.instant();
        if (Duration.between(cachedAt, now).compareTo(CACHE) < 0) {
            return cached;
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        try {
            for (var q : QUERIES.entrySet()) {
                out.put(q.getKey(), prometheus.byJob(q.getValue()));
            }
            cached = out;
        } catch (Exception e) {
            cached = null;
        }
        cachedAt = now;
        return cached;
    }
}
