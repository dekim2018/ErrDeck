package com.errdeck.worker.out.clickhouse;

import com.errdeck.core.event.Transaction;
import com.errdeck.core.json.Json;
import com.errdeck.worker.app.EventStore;
import com.errdeck.worker.config.ClickHouseProperties;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.Tick;
import com.errdeck.worker.domain.TransactionRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 묶음을 ClickHouse 의 HTTP 인터페이스로 테이블마다 한 번에 넣는다. 행은 한 줄에 JSON 하나(JSONEachRow)로 보낸다.
 * 묶음 하나가 파트 하나가 되므로 작게 여러 번 쓰지 않는다(10.4).
 */
public class ClickHouseEventStore implements EventStore, com.errdeck.worker.uptime.UptimeRunner.UptimeWriter,
        com.errdeck.worker.alert.AlertEngine.FrequencySource {

    private static final String EVENTS = "INSERT INTO events (project_id, event_id, fingerprint, timestamp, received_at, "
            + "environment, release, level, exc_type, message, user_hash, trace_id, tags, restored, payload) FORMAT JSONEachRow";
    private static final String TRANSACTIONS = "INSERT INTO transactions (project_id, trace_id, span_id, parent_span_id, name, op, "
            + "timestamp, received_at, duration_ms, status, partial, environment, release, span_ids, span_parent_ids, span_ops, "
            + "span_descriptions, span_starts_ms, span_durations_ms, span_repeats) FORMAT JSONEachRow";
    private static final String TICKS = "INSERT INTO event_ticks (project_id, fingerprint, timestamp, count, user_hash) FORMAT JSONEachRow";

    private static final String NO_PARENT = "0000000000000000";

    private final HttpClient client;
    private final ClickHouseProperties properties;

    public ClickHouseEventStore(ClickHouseProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public void writeEvents(List<EventRow> rows) {
        insert(EVENTS, rows, ClickHouseEventStore::event);
    }

    @Override
    public void writeTicks(List<Tick> ticks) {
        insert(TICKS, ticks, ClickHouseEventStore::tick);
    }

    @Override
    public void writeTransactions(List<TransactionRow> rows) {
        insert(TRANSACTIONS, rows, ClickHouseEventStore::transaction);
    }

    private static final String UPTIME = "INSERT INTO uptime_checks (monitor_id, project_id, timestamp, ok, status_code, duration_ms, "
            + "error, cert_expires_at) FORMAT JSONEachRow";

    @Override
    public void write(List<com.errdeck.worker.uptime.CheckResult> results) {
        insert(UPTIME, results, r -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("monitor_id", r.monitorId());
            json.put("project_id", r.projectId());
            json.put("timestamp", r.timestamp().toString());
            json.put("ok", r.ok() ? 1 : 0);
            json.put("status_code", r.statusCode());
            json.put("duration_ms", Math.min(r.durationMs(), 0xFFFF_FFFFL));
            json.put("error", r.error() == null ? "" : r.error());
            json.put("cert_expires_at", r.certExpiresAt() == null ? null : r.certExpiresAt().getEpochSecond());
            return json;
        });
    }

    /** 빈도 규칙(16.1). 분 단위 집계에서 구간 동안 기준 이상인 지문. */
    @Override
    public Map<String, Long> over(long projectId, java.time.Duration window, long threshold) {
        String sql = "SELECT fingerprint, sum(times) AS t FROM issue_stats_1m WHERE project_id = {p:UInt32} "
                + "AND minute >= now() - toIntervalMinute({w:UInt32}) GROUP BY fingerprint HAVING t >= {th:UInt64} FORMAT JSONEachRow";
        URI uri = properties.url().resolve("/?database=" + encode(properties.database()) + "&param_p=" + projectId
                + "&param_w=" + window.toMinutes() + "&param_th=" + threshold);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.timeout())
                .header("X-ClickHouse-User", properties.user())
                .header("X-ClickHouse-Key", properties.password())
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ClickHouse 가 " + response.statusCode() + " 로 거절했다: " + response.body().strip());
            }
            Map<String, Long> out = new LinkedHashMap<>();
            for (String line : response.body().split("\n")) {
                if (!line.isBlank() && Json.parse(line) instanceof Map<?, ?> row) {
                    out.put(String.valueOf(row.get("fingerprint")), Long.parseLong(String.valueOf(row.get("t"))));
                }
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ClickHouse 를 읽지 못했다: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClickHouse 읽기 중 중단되었다", e);
        }
    }

    private <T> void insert(String statement, List<T> rows, Function<T, Map<String, Object>> toJson) {
        if (rows.isEmpty()) {
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(rows.size() * 256);
        for (T row : rows) {
            body.writeBytes(Json.writeBytes(toJson.apply(row)));
            body.write('\n');
        }
        // 시각은 ISO-8601 UTC 문자열로 보내므로 시간대가 붙은 값을 읽게 한다.
        URI uri = properties.url().resolve("/?database=" + encode(properties.database())
                + "&date_time_input_format=best_effort&query=" + encode(statement));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.timeout())
                .header("X-ClickHouse-User", properties.user())
                .header("X-ClickHouse-Key", properties.password())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("ClickHouse 에 쓰지 못했다: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClickHouse 쓰기 중 중단되었다", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ClickHouse 가 " + response.statusCode() + " 로 거절했다: " + response.body().strip());
        }
    }

    private static Map<String, Object> event(EventRow row) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("project_id", row.projectId());
        json.put("event_id", row.eventId().toString());
        json.put("fingerprint", row.fingerprint());
        json.put("timestamp", row.timestamp().toString());
        json.put("received_at", row.receivedAt() == null ? row.timestamp().toString() : row.receivedAt().toString());
        json.put("environment", row.environment());
        json.put("release", row.release());
        json.put("level", row.level());
        json.put("exc_type", row.excType());
        json.put("message", row.message());
        json.put("user_hash", row.userHash());
        json.put("trace_id", row.traceId());
        json.put("tags", row.tags());
        json.put("restored", row.restored() ? 1L : 0L);
        json.put("payload", row.payload());
        return json;
    }

    private static Map<String, Object> transaction(TransactionRow row) {
        Transaction tx = row.transaction();
        List<Transaction.Span> spans = tx.spans();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("project_id", row.projectId());
        json.put("trace_id", tx.traceId());
        json.put("span_id", tx.spanId());
        // FixedString 열이라 없는 부모는 0 으로 채운다. 화면은 0 을 "처음 시작한 곳"으로 읽는다.
        json.put("parent_span_id", tx.parentSpanId() == null ? NO_PARENT : tx.parentSpanId());
        json.put("name", tx.name());
        json.put("op", tx.op());
        json.put("timestamp", tx.start().toString());
        json.put("received_at", row.receivedAt() == null ? tx.start().toString() : row.receivedAt().toString());
        json.put("duration_ms", Math.min(tx.durationMs(), 0xFFFF_FFFFL));
        json.put("status", tx.status());
        json.put("partial", tx.partial() ? 1 : 0);
        json.put("environment", tx.environment() == null ? "" : tx.environment());
        json.put("release", tx.release() == null ? "" : tx.release());
        json.put("span_ids", spans.stream().map(Transaction.Span::spanId).toList());
        json.put("span_parent_ids", spans.stream().map(s -> s.parentSpanId() == null ? NO_PARENT : s.parentSpanId()).toList());
        json.put("span_ops", spans.stream().map(Transaction.Span::op).toList());
        json.put("span_descriptions", spans.stream().map(Transaction.Span::description).toList());
        json.put("span_starts_ms", spans.stream().map(Transaction.Span::startOffsetMs).toList());
        json.put("span_durations_ms", spans.stream().map(Transaction.Span::durationMs).toList());
        json.put("span_repeats", spans.stream().map(Transaction.Span::repeat).toList());
        return json;
    }

    private static Map<String, Object> tick(Tick tick) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("project_id", tick.projectId());
        json.put("fingerprint", tick.fingerprint());
        json.put("timestamp", tick.timestamp().toString());
        // UInt32 열이다. SDK 가 보낸 count 가 그보다 크면 상한으로 자른다.
        json.put("count", Math.min(tick.count(), 0xFFFF_FFFFL));
        json.put("user_hash", tick.userHash());
        return json;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
