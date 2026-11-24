package com.errdeck.api.app;

import com.errdeck.api.app.ApiException.Kind;
import com.errdeck.api.app.EventStats.Bucket;
import com.errdeck.api.app.EventStats.Counts;
import com.errdeck.api.app.IssueReadRepository.Comment;
import com.errdeck.api.app.IssueReadRepository.IssueRow;
import com.errdeck.api.domain.ProjectRef;
import com.errdeck.api.domain.Role;
import com.errdeck.core.json.Json;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 이슈 목록과 상세(12.3). 상태와 시각은 PostgreSQL 에서, 횟수와 사용자 수와 원본은 ClickHouse 에서 읽어 합친다(6.5).
 */
@Service
public class IssueQueryService {

    public static final int PAGE_SIZE = 25;
    /** 횟수순 정렬에서 ClickHouse 가 먼저 고르는 지문 수. 상태와 릴리스로 거른 뒤 25건을 채우려고 넉넉히 받는다. */
    private static final int TOP_CANDIDATES = 200;

    public enum Sort {
        RECENT, FREQUENT, USERS
    }

    public enum Period {
        HOUR_1(Duration.ofHours(1), Duration.ofMinutes(5)),
        HOURS_24(Duration.ofHours(24), Duration.ofHours(1)),
        DAYS_7(Duration.ofDays(7), Duration.ofHours(6)),
        DAYS_14(Duration.ofDays(14), Duration.ofDays(1)),
        DAYS_30(Duration.ofDays(30), Duration.ofDays(1));

        final Duration length;
        final Duration bucket;

        Period(Duration length, Duration bucket) {
            this.length = length;
            this.bucket = bucket;
        }

        public static Period parse(String value) {
            return switch (value) {
                case "1h" -> HOUR_1;
                case "24h" -> HOURS_24;
                case "7d" -> DAYS_7;
                case "14d" -> DAYS_14;
                case "30d" -> DAYS_30;
                default -> throw new ApiException(Kind.BAD_REQUEST, "기간은 1h, 24h, 7d, 14d, 30d 중 하나다");
            };
        }
    }

    public record IssueSummary(long id, String status, String title, String culprit, Instant firstSeen, Instant lastSeen,
                               String lastRelease, long count, long users) {
    }

    public record IssueDetail(IssueSummary issue, String resolvedInRelease, Instant ignoredUntil, List<String> fingerprints,
                              List<Bucket> trend, Object latestEvent, Instant latestEventAt, String latestEnvironment, String traceId,
                              List<Comment> comments, String grafanaUrl) {
    }

    private final IssueReadRepository issues;
    private final EventStats stats;
    private final AccessPolicy access;
    private final URI grafanaUrl;
    private final Clock clock;

    public IssueQueryService(IssueReadRepository issues, EventStats stats, AccessPolicy access,
                             com.errdeck.api.config.ConsoleProperties console, Clock clock) {
        this.issues = issues;
        this.stats = stats;
        this.access = access;
        this.grafanaUrl = console.grafanaUrl();
        this.clock = clock;
    }

    public List<IssueSummary> list(long userId, long projectId, String status, String release, Period period, Sort sort, int page) {
        access.requireProject(userId, projectId, Role.VIEWER);
        Instant to = clock.instant();
        Instant from = to.minus(period.length);
        List<IssueRow> rows;
        if (sort == Sort.RECENT) {
            rows = issues.recent(projectId, status, release, PAGE_SIZE, Math.max(0, page) * PAGE_SIZE);
        } else {
            List<String> top = stats.topFingerprints(projectId, from, to, sort == Sort.USERS, TOP_CANDIDATES);
            rows = top.isEmpty() ? List.of() : issues.byFingerprints(projectId, top, status, release);
        }
        List<IssueSummary> summaries = summarize(projectId, rows, from, to);
        if (sort != Sort.RECENT) {
            Comparator<IssueSummary> order = sort == Sort.USERS
                    ? Comparator.comparingLong(IssueSummary::users).thenComparingLong(IssueSummary::count)
                    : Comparator.comparingLong(IssueSummary::count);
            summaries = summaries.stream().sorted(order.reversed()).limit(PAGE_SIZE).toList();
        }
        return summaries;
    }

    public IssueDetail detail(long userId, long issueId, Period period) {
        ProjectRef ref = access.requireIssue(userId, issueId, Role.VIEWER);
        IssueRow row = issues.find(issueId).orElseThrow(() -> ApiException.notFound("이슈"));
        Instant to = clock.instant();
        Instant from = to.minus(period.length);
        IssueSummary summary = summarize(ref.projectId(), List.of(row), from, to).getFirst();
        List<Bucket> trend = fill(stats.trend(ref.projectId(), row.fingerprints(), from, to, period.bucket.toSeconds()),
                from, to, period.bucket);
        // 원본은 30일만 남는다(10.2). 기간과 상관없이 가장 최근 원본을 보여 준다.
        var latest = stats.latestEvent(ref.projectId(), row.fingerprints(), to.minus(Duration.ofDays(30)), to);
        return new IssueDetail(summary, row.resolvedInRelease(), row.ignoredUntil(), row.fingerprints(), trend,
                latest.map(e -> Json.parse(e.payload())).orElse(null),
                latest.map(EventStats.LatestEvent::timestamp).orElse(null),
                latest.map(EventStats.LatestEvent::environment).orElse(null),
                latest.map(EventStats.LatestEvent::traceId).filter(t -> !t.isBlank() && !t.chars().allMatch(c -> c == 0)).orElse(null),
                issues.comments(issueId),
                grafanaLink(issues.projectSlug(ref.projectId()), row.firstSeen()));
    }

    /**
     * 발생이 없는 구간도 0으로 채운다. 그래야 막대 하나가 화면을 다 채우지 않고 기간 전체의 모양이 보인다.
     * 구간의 시작은 ClickHouse 의 toStartOfInterval 과 같게 1970년부터 구간 길이로 자른 시각이다.
     */
    static List<Bucket> fill(List<Bucket> buckets, Instant from, Instant to, Duration bucket) {
        Map<Instant, Long> byStart = new LinkedHashMap<>();
        buckets.forEach(b -> byStart.put(b.start(), b.times()));
        long size = bucket.toSeconds();
        List<Bucket> filled = new ArrayList<>();
        for (long t = Math.floorDiv(from.getEpochSecond(), size) * size; t < to.getEpochSecond(); t += size) {
            Instant start = Instant.ofEpochSecond(t);
            filled.add(new Bucket(start, byStart.getOrDefault(start, 0L)));
        }
        return filled;
    }

    private List<IssueSummary> summarize(long projectId, List<IssueRow> rows, Instant from, Instant to) {
        List<String> fingerprints = rows.stream().flatMap(r -> r.fingerprints().stream()).distinct().toList();
        Map<String, Counts> counts = fingerprints.isEmpty() ? Map.of() : stats.counts(projectId, fingerprints, from, to);
        List<IssueSummary> out = new ArrayList<>(rows.size());
        for (IssueRow row : rows) {
            long times = 0;
            long users = 0;
            for (String fp : row.fingerprints()) {
                Counts c = counts.get(fp);
                if (c != null) {
                    times += c.times();
                    // 지문이 여럿이면 사용자를 더해 실제보다 많게 셀 수 있다. 대부분 이슈는 지문이 하나다(6.4).
                    users += c.users();
                }
            }
            out.add(new IssueSummary(row.id(), row.status(), row.title(), row.culprit(), row.firstSeen(), row.lastSeen(),
                    row.lastRelease(), times, users));
        }
        return out;
    }

    /** 최초 발생 시각의 앞뒤 15분으로 맞춘 그라파나 링크(12.4). 대시보드는 16 작업에서 만든다. */
    private String grafanaLink(String service, Instant at) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("var-job", service);
        query.put("from", Long.toString(at.minus(Duration.ofMinutes(15)).toEpochMilli()));
        query.put("to", Long.toString(at.plus(Duration.ofMinutes(15)).toEpochMilli()));
        StringBuilder url = new StringBuilder(grafanaUrl.toString().replaceAll("/$", "")).append("/d/errdeck-jvm?");
        query.forEach((k, v) -> url.append(k).append('=').append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)).append('&'));
        return url.substring(0, url.length() - 1);
    }
}
