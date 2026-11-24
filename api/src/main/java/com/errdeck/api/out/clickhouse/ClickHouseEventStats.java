package com.errdeck.api.out.clickhouse;

import com.errdeck.api.app.EventStats;
import com.errdeck.api.app.query.ScopedQuery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 건수는 분 단위 집계(issue_stats_1m)만 읽는다. 원본을 상한으로 줄여도 숫자가 변하지 않게 하기 위해서다(10.3).
 * 원본(events)은 이슈 상세의 최근 이벤트 하나만 읽는다.
 */
public class ClickHouseEventStats implements EventStats {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private final ClickHouseReader reader;

    public ClickHouseEventStats(ClickHouseReader reader) {
        this.reader = reader;
    }

    @Override
    public Map<String, Counts> counts(long projectId, Collection<String> fingerprints, Instant from, Instant to) {
        Map<String, Counts> counts = new HashMap<>();
        for (Map<String, Object> row : reader.query(ScopedQuery.from("issue_stats_1m", Set.of(projectId), "minute", from, to)
                .select("fingerprint", "sum(times) AS times", "uniqMerge(users) AS users")
                .where("fingerprint IN {fps:Array(String)}", "fps", fingerprints)
                .groupBy("fingerprint"))) {
            counts.put((String) row.get("fingerprint"), new Counts(number(row.get("times")), number(row.get("users"))));
        }
        return counts;
    }

    @Override
    public List<String> topFingerprints(long projectId, Instant from, Instant to, boolean byUsers, int limit) {
        return reader.query(ScopedQuery.from("issue_stats_1m", Set.of(projectId), "minute", from, to)
                        .select("fingerprint", "sum(times) AS times", "uniqMerge(users) AS users")
                        .groupBy("fingerprint")
                        .orderBy(byUsers ? "users DESC" : "times DESC")
                        .limit(limit))
                .stream()
                .map(row -> (String) row.get("fingerprint"))
                .toList();
    }

    @Override
    public List<Bucket> trend(long projectId, Collection<String> fingerprints, Instant from, Instant to, long bucketSeconds) {
        return reader.query(ScopedQuery.from("issue_stats_1m", Set.of(projectId), "minute", from, to)
                        .select("toStartOfInterval(minute, toIntervalSecond(" + bucketSeconds + ")) AS bucket", "sum(times) AS times")
                        .where("fingerprint IN {fps:Array(String)}", "fps", fingerprints)
                        .groupBy("bucket")
                        .orderBy("bucket"))
                .stream()
                .map(row -> new Bucket(time(row.get("bucket")), number(row.get("times"))))
                .toList();
    }

    @Override
    public Optional<LatestEvent> latestEvent(long projectId, Collection<String> fingerprints, Instant from, Instant to) {
        return reader.query(ScopedQuery.from("events", Set.of(projectId), "timestamp", from, to)
                        .select("payload", "timestamp", "environment", "release", "trace_id")
                        .where("fingerprint IN {fps:Array(String)}", "fps", fingerprints)
                        .orderBy("timestamp DESC")
                        .limit(1))
                .stream()
                .findFirst()
                .map(row -> new LatestEvent((String) row.get("payload"), time(row.get("timestamp")), (String) row.get("environment"),
                        (String) row.get("release"), ((String) row.get("trace_id")).replace("\u0000", "")));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    /** ClickHouse 는 서버 시간대(UTC)의 {@code yyyy-MM-dd HH:mm:ss[.SSS]} 로 돌려준다. */
    private static Instant time(Object value) {
        return LocalDateTime.parse(String.valueOf(value), CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC);
    }
}
