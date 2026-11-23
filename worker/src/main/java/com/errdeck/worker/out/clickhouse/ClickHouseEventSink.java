package com.errdeck.worker.out.clickhouse;

import com.errdeck.core.json.Json;
import com.errdeck.worker.app.EventSink;
import com.errdeck.worker.config.ClickHouseProperties;
import com.errdeck.worker.domain.EventRow;
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

/**
 * 이벤트 묶음을 ClickHouse 의 HTTP 인터페이스로 한 번에 넣는다. 행은 한 줄에 JSON 하나(JSONEachRow)로 보낸다.
 * 묶음 하나가 파트 하나가 되므로 작게 여러 번 쓰지 않는다(10.4).
 */
public class ClickHouseEventSink implements EventSink {

    private static final String INSERT = "INSERT INTO events (project_id, event_id, fingerprint, timestamp, received_at, "
            + "environment, release, level, exc_type, message, user_hash, trace_id, tags, restored, payload) FORMAT JSONEachRow";

    private final HttpClient client;
    private final URI uri;
    private final ClickHouseProperties properties;

    public ClickHouseEventSink(ClickHouseProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        // 시각은 ISO-8601 UTC 문자열로 보내므로 시간대가 붙은 값을 읽게 한다.
        this.uri = properties.url().resolve("/?database=" + encode(properties.database())
                + "&date_time_input_format=best_effort&query=" + encode(INSERT));
    }

    @Override
    public void write(List<EventRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(rows.size() * 512);
        for (EventRow row : rows) {
            body.writeBytes(Json.writeBytes(toJson(row)));
            body.write('\n');
        }
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

    private static Map<String, Object> toJson(EventRow row) {
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
