package com.errdeck.core.event;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventTest {

    private final Envelope example = new EnvelopeCodec(EnvelopeLimits.DEFAULT)
            .read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));

    @Test
    void 예제_봉투의_이벤트를_읽고_다시_쓰면_같은_바이트다() {
        for (int i : new int[] {0, 1}) {
            byte[] payload = example.items().get(i).payload();

            assertArrayEquals(payload, Event.fromJson(payload).toJson(), "항목 " + i);
        }
    }

    @Test
    void 서버_에러의_예외_사슬과_요청_정보와_빵부스러기를_읽는다() {
        Event event = Event.fromJson(example.items().get(0).payload());

        assertEquals(UUID.fromString("0f3c2a5e-8b1d-4c7a-9e2f-6a4b8c1d2e3f"), event.eventId());
        assertEquals(Instant.parse("2026-11-23T10:00:00.123Z"), event.timestamp());
        assertEquals("prod", event.environment());
        assertEquals("interaction-service@1.4.2", event.release());
        assertEquals(7, event.count());
        assertEquals(List.of("org.springframework.dao.DataAccessResourceFailureException", "java.sql.SQLTransientConnectionException"),
                event.exceptions().stream().map(ExceptionInfo::type).toList());
        assertEquals("org.springframework.dao.DataAccessResourceFailureException", event.primaryException().type());
        assertEquals(new StackFrame("com.livebroadcast.interaction.payment.PaymentService", "charge", "PaymentService.java", 57, null, true),
                event.exceptions().get(0).frames().get(2));
        assertEquals("POST", event.request().method());
        assertEquals("[삭제됨]", event.request().headers().get("Authorization"));
        assertEquals(Map.of("region", "ap-seoul", "handler", "PaymentController#pay"), event.tags());
        assertEquals("줄 바꿈이\n섞인 \"따옴표\" 메시지\t탭", event.breadcrumbs().get(1).message());
    }

    @Test
    void 브라우저_에러의_열_번호를_읽고_판정하지_않은_앱_프레임은_비워_둔다() {
        Event event = Event.fromJson(example.items().get(1).payload());

        StackFrame frame = event.exceptions().getFirst().frames().getFirst();
        assertEquals(48213, frame.colno());
        assertNull(frame.inApp());
        assertNull(frame.module());
        assertEquals(1, event.count());
    }

    @Test
    void 필수_필드만_있으면_기본값을_채운다() {
        Event event = Event.fromJson(bytes("{\"event_id\":\"" + UUID.randomUUID() + "\",\"timestamp\":\"2026-11-23T10:00:00Z\"}"));

        assertEquals("error", event.level());
        assertEquals(1, event.count());
        assertEquals(List.of(), event.exceptions());
        assertEquals(Map.of(), event.tags());
        assertNull(event.primaryException());
    }

    @Test
    void 만든_이벤트를_쓰고_읽으면_같다() {
        Event event = new Event(UUID.randomUUID(), Instant.parse("2026-11-23T10:00:00.5Z"), "warning", "메시지",
                "dev", "r1", "u1", "0123456789abcdef0123456789abcdef", List.of("payment-timeout"), 3,
                List.of(new ExceptionInfo("java.lang.IllegalStateException", null, List.of(new StackFrame("a.B", "c", "B.java", 1, null, true)))),
                new RequestInfo("GET", "http://x/y", null, Map.of("Accept", "*/*")),
                Map.of("k", "v"),
                List.of(new Breadcrumb(Instant.parse("2026-11-23T09:00:00Z"), "log", "m", Map.of())));

        assertEquals(event, Event.fromJson(event.toJson()));
    }

    @Test
    void 형식이_틀린_이벤트를_거부한다() {
        String ts = "\"timestamp\":\"2026-11-23T10:00:00Z\"";
        String id = "\"event_id\":\"0f3c2a5e-8b1d-4c7a-9e2f-6a4b8c1d2e3f\"";

        assertRejected("not json");
        assertRejected("[]");
        assertRejected("{" + ts + "}");
        assertRejected("{" + id + "}");
        assertRejected("{\"event_id\":\"1-1-1-1-1\"," + ts + "}");
        assertRejected("{\"event_id\":\"0F3C2A5E-8B1D-4C7A-9E2F-6A4B8C1D2E3F\"," + ts + "}");
        assertRejected("{" + id + ",\"timestamp\":\"2026-11-23 10:00:00\"}");
        assertRejected("{" + id + "," + ts + ",\"level\":\"debug\"}");
        assertRejected("{" + id + "," + ts + ",\"count\":0}");
        assertRejected("{" + id + "," + ts + ",\"count\":\"7\"}");
        assertRejected("{" + id + "," + ts + ",\"trace_id\":\"xyz\"}");
        assertRejected("{" + id + "," + ts + ",\"exceptions\":{}}");
        assertRejected("{" + id + "," + ts + ",\"exceptions\":[{\"value\":\"타입이 없다\"}]}");
        assertRejected("{" + id + "," + ts + ",\"exceptions\":[{\"type\":\"E\",\"frames\":[{\"lineno\":\"1\"}]}]}");
        assertRejected("{" + id + "," + ts + ",\"tags\":{\"k\":1}}");
        assertRejected("{" + id + "," + ts + ",\"breadcrumbs\":[{\"message\":\"시각이 없다\"}]}");
    }

    @Test
    void client_report를_읽고_다시_쓰면_같은_바이트다() {
        byte[] payload = example.items().get(3).payload();

        ClientReport report = ClientReport.fromJson(payload);

        assertEquals(List.of(new ClientReport.Discarded("queue_overflow", "event", 12),
                new ClientReport.Discarded("rate_limited", "transaction", 340)), report.discarded());
        assertArrayEquals(payload, report.toJson());
    }

    @Test
    void 형식이_틀린_client_report를_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> ClientReport.fromJson(bytes("{\"discarded\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> ClientReport.fromJson(
                bytes("{\"timestamp\":\"2026-11-23T10:00:00Z\",\"discarded\":[{\"reason\":\"r\",\"category\":\"event\",\"quantity\":0}]}")));
    }

    private static void assertRejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> Event.fromJson(bytes(json)), json);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
