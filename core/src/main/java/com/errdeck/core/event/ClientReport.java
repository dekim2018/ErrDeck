package com.errdeck.core.event;

import com.errdeck.core.json.Json;
import com.errdeck.core.json.JsonException;
import com.errdeck.core.json.JsonFields;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * SDK가 보내지 못하고 버린 건수. 봉투의 client_report 항목 본문이다(5.6).
 * 버린 것도 세어야 이슈의 발생 건수가 맞는다.
 */
public record ClientReport(Instant timestamp, List<Discarded> discarded) {

    public ClientReport {
        Objects.requireNonNull(timestamp, "timestamp");
        discarded = discarded == null ? List.of() : List.copyOf(discarded);
    }

    /**
     * 사유와 종류별 버린 건수.
     *
     * @param reason   버린 이유. 예: {@code queue_overflow}, {@code rate_limited}, {@code queue_full}, {@code too_large}
     * @param category 버린 항목의 종류. {@code event} 나 {@code transaction}
     */
    public record Discarded(String reason, String category, long quantity) {

        public Discarded {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(category, "category");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity 는 1 이상이어야 한다: " + quantity);
            }
        }
    }

    public static ClientReport fromJson(byte[] utf8) {
        try {
            JsonFields f = JsonFields.of(Json.parseObject(utf8), "client_report");
            return new ClientReport(
                    Event.parseInstant(f.requireString("timestamp"), "client_report.timestamp"),
                    f.list("discarded", e -> {
                        JsonFields d = e.asObject();
                        return new Discarded(d.requireString("reason"), d.requireString("category"), d.requireLong("quantity"));
                    }));
        } catch (JsonException e) {
            throw new IllegalArgumentException("client_report 형식이 틀렸다: " + e.getMessage(), e);
        }
    }

    public byte[] toJson() {
        return Json.writeBytes(new JsonOut()
                .put("timestamp", timestamp.toString())
                .put("discarded", discarded.stream()
                        .map(d -> new JsonOut()
                                .put("reason", d.reason())
                                .put("category", d.category())
                                .put("quantity", d.quantity())
                                .build())
                        .toList())
                .build());
    }
}
