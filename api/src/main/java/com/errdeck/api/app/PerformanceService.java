package com.errdeck.api.app;

import com.errdeck.api.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 성능 화면(8.1, 12.3). 엔드포인트가 얼마나 느린지는 프로메테우스의 전체 요청으로, 왜 느린지는 표본 트랜잭션의 스팬으로 답한다.
 * 프로메테우스가 없거나 그 프로젝트를 수집하지 않으면 표본으로 계산한 표를 내고 그렇다고 표시한다.
 */
@Service
public class PerformanceService {

    /** 트랜잭션 보존 기간(10.1). 추적 조회는 이 안에서 찾는다. */
    static final Duration RETENTION = Duration.ofDays(7);
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    public enum Source { PROMETHEUS, SAMPLES }

    public record Endpoint(String name, long requests, double p95Ms, double errorRate) {
    }

    public record EndpointTable(Source source, List<Endpoint> endpoints) {
    }

    public record SpanView(String spanId, String parentSpanId, String op, String description, long startOffsetMs, long durationMs,
                           int repeat) {
    }

    public record TransactionView(long projectId, String traceId, String spanId, String parentSpanId, String name, String op,
                                  Instant timestamp, long durationMs, String status, boolean partial, String release,
                                  List<SpanView> spans) {
    }

    private final TraceStore traces;
    private final EndpointMetrics metrics;
    private final AccessPolicy access;
    private final WorkspaceRepository workspace;
    private final Clock clock;

    public PerformanceService(TraceStore traces, EndpointMetrics metrics, AccessPolicy access, WorkspaceRepository workspace,
                              Clock clock) {
        this.traces = traces;
        this.metrics = metrics;
        this.access = access;
        this.workspace = workspace;
        this.clock = clock;
    }

    public EndpointTable endpoints(long userId, long projectId, Duration period) {
        access.requireProject(userId, projectId, Role.VIEWER);
        Duration window = clamp(period);
        String job = workspace.projectDetail(projectId).slug();
        List<Endpoint> fromPrometheus = metrics.endpoints(job, window);
        if (fromPrometheus != null && !fromPrometheus.isEmpty()) {
            return new EndpointTable(Source.PROMETHEUS, fromPrometheus);
        }
        Instant to = clock.instant();
        return new EndpointTable(Source.SAMPLES, traces.endpoints(projectId, to.minus(window), to));
    }

    public List<TransactionView> slowest(long userId, long projectId, String name, Duration period) {
        access.requireProject(userId, projectId, Role.VIEWER);
        Instant to = clock.instant();
        return traces.slowest(projectId, name, to.minus(clamp(period)), to, 50);
    }

    /** 한 요청의 흐름. 서비스(프로젝트) 사이를 trace_id 로 잇되, 이 사용자가 볼 수 있는 프로젝트의 것만 보인다. */
    public List<TransactionView> trace(long userId, String traceId) {
        if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "trace_id 는 소문자 16진수 32자다");
        }
        Set<Long> visible = workspace.visibleProjectIds(userId);
        Instant to = clock.instant();
        List<TransactionView> found = traces.trace(visible, traceId, to.minus(RETENTION), to.plusSeconds(60));
        if (found.isEmpty()) {
            throw new ApiException(ApiException.Kind.NOT_FOUND, "추적이 없거나 보존 기간(7일)이 지났다");
        }
        return found;
    }

    private static Duration clamp(Duration period) {
        return period.compareTo(RETENTION) > 0 ? RETENTION : period;
    }

    /** 트랜잭션을 읽는 곳. */
    public interface TraceStore {
        List<Endpoint> endpoints(long projectId, Instant from, Instant to);

        List<TransactionView> slowest(long projectId, String name, Instant from, Instant to, int limit);

        List<TransactionView> trace(Set<Long> projectIds, String traceId, Instant from, Instant to);
    }

    /** 감시 대상 전체 요청의 지표. 못 읽으면 null 이다(14.9: 프로메테우스 2초). */
    public interface EndpointMetrics {
        List<Endpoint> endpoints(String job, Duration window);
    }
}
