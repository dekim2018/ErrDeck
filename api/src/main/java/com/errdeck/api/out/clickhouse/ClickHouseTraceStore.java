package com.errdeck.api.out.clickhouse;

import com.errdeck.api.app.PerformanceService.Endpoint;
import com.errdeck.api.app.PerformanceService.SpanView;
import com.errdeck.api.app.PerformanceService.TraceStore;
import com.errdeck.api.app.PerformanceService.TransactionView;
import com.errdeck.api.app.query.ScopedQuery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** transactions 테이블을 읽는다(10.1). 스팬은 열마다 배열이라 같은 위치끼리 묶어 하나로 돌려준다. */
public class ClickHouseTraceStore implements TraceStore {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");
    private static final String NO_PARENT = "0000000000000000";
    private static final String[] COLUMNS = {"project_id", "trace_id", "span_id", "parent_span_id", "name", "op", "timestamp",
            "duration_ms", "status", "partial", "release", "span_ids", "span_parent_ids", "span_ops", "span_descriptions",
            "span_starts_ms", "span_durations_ms", "span_repeats"};

    private final ClickHouseReader reader;

    public ClickHouseTraceStore(ClickHouseReader reader) {
        this.reader = reader;
    }

    @Override
    public List<Endpoint> endpoints(long projectId, Instant from, Instant to) {
        return reader.query(ScopedQuery.from("transactions", Set.of(projectId), "timestamp", from, to)
                        .select("name", "count() AS requests", "quantile(0.95)(duration_ms) AS p95",
                                "countIf(status = 'error' OR startsWith(status, '5')) / count() AS error_rate")
                        .groupBy("name")
                        .orderBy("requests DESC")
                        .limit(100))
                .stream()
                .map(r -> new Endpoint((String) r.get("name"), number(r.get("requests")), decimal(r.get("p95")), decimal(r.get("error_rate"))))
                .toList();
    }

    @Override
    public List<TransactionView> slowest(long projectId, String name, Instant from, Instant to, int limit) {
        ScopedQuery query = ScopedQuery.from("transactions", Set.of(projectId), "timestamp", from, to).select(COLUMNS);
        if (name != null && !name.isBlank()) {
            query.where("name = {tx_name:String}", "tx_name", name);
        }
        return reader.query(query.orderBy("duration_ms DESC").limit(limit)).stream().map(ClickHouseTraceStore::view).toList();
    }

    @Override
    public List<TransactionView> trace(Set<Long> projectIds, String traceId, Instant from, Instant to) {
        return reader.query(ScopedQuery.from("transactions", projectIds, "timestamp", from, to)
                        .select(COLUMNS)
                        .where("trace_id = {trace:FixedString(32)}", "trace", traceId)
                        .orderBy("timestamp")
                        .limit(200))
                .stream()
                .map(ClickHouseTraceStore::view)
                .toList();
    }

    private static TransactionView view(Map<String, Object> r) {
        List<?> ids = (List<?>) r.get("span_ids");
        List<?> parents = (List<?>) r.get("span_parent_ids");
        List<?> ops = (List<?>) r.get("span_ops");
        List<?> descriptions = (List<?>) r.get("span_descriptions");
        List<?> starts = (List<?>) r.get("span_starts_ms");
        List<?> durations = (List<?>) r.get("span_durations_ms");
        List<?> repeats = (List<?>) r.get("span_repeats");
        List<SpanView> spans = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            spans.add(new SpanView((String) ids.get(i), parent((String) parents.get(i)), (String) ops.get(i), (String) descriptions.get(i),
                    number(starts.get(i)), number(durations.get(i)), (int) number(repeats.get(i))));
        }
        return new TransactionView(number(r.get("project_id")), (String) r.get("trace_id"), (String) r.get("span_id"),
                parent((String) r.get("parent_span_id")), (String) r.get("name"), (String) r.get("op"), time(r.get("timestamp")),
                number(r.get("duration_ms")), (String) r.get("status"), number(r.get("partial")) == 1, (String) r.get("release"), spans);
    }

    private static String parent(String value) {
        return value == null || NO_PARENT.equals(value) || value.isBlank() ? null : value;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static double decimal(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private static Instant time(Object value) {
        return LocalDateTime.parse(String.valueOf(value), CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC);
    }
}
