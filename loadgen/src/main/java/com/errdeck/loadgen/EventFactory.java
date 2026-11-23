package com.errdeck.loadgen;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeHeader;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.Event;
import com.errdeck.core.event.RequestInfo;
import com.errdeck.core.event.StackFrame;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spring SDK 가 보낼 것과 같은 모양의 봉투를 만든다. 형식은 core 의 봉투 코덱을 그대로 쓴다.
 *
 * <p>지문 번호마다 예외 타입과 앱 프레임의 메서드 이름이 다르다. 지문 규칙(6.3)이 이 두 가지로 지문을 만들기 때문이다.
 * 줄 번호는 매번 바꿔, 줄 번호만 다른 같은 에러가 섞이게 한다.
 */
final class EventFactory {

    static final String SDK = "loadgen/0.1.0";

    private final Options options;
    private final EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
    private final Clock clock;

    EventFactory(Options options, Clock clock) {
        this.options = options;
        this.clock = clock;
    }

    /** 압축된 봉투 하나. */
    byte[] nextEnvelope() {
        List<EnvelopeItem> items = new ArrayList<>(options.batch());
        for (int i = 0; i < options.batch(); i++) {
            items.add(new EnvelopeItem(ItemType.EVENT, nextEvent().toJson()));
        }
        return codec.encode(new Envelope(new EnvelopeHeader(SDK, clock.instant()), items));
    }

    Event nextEvent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int fingerprint = random.nextInt(options.fingerprints());
        String service = "com.errdeck.loadgen.demo.Service" + fingerprint;
        return new Event(
                UUID.randomUUID(),
                clock.instant(),
                "error",
                null,
                "loadgen",
                "loadgen@0.1.0",
                "user-" + random.nextInt(1000),
                null,
                List.of(),
                1,
                List.of(
                        new ExceptionInfo("com.errdeck.loadgen.demo.LoadGenException" + fingerprint,
                                "부하 실험 에러 " + fingerprint + " (요청 " + random.nextInt(1_000_000) + ")",
                                List.of(
                                        new StackFrame(service, "handle" + fingerprint, "Service.java", 10 + random.nextInt(200), null, true),
                                        new StackFrame("com.errdeck.loadgen.demo.Controller", "call", "Controller.java", 42, null, true),
                                        new StackFrame("org.springframework.web.servlet.FrameworkServlet", "service", "FrameworkServlet.java", 914, null, false))),
                        new ExceptionInfo("java.sql.SQLTransientConnectionException", "Connection is not available",
                                List.of(new StackFrame("com.zaxxer.hikari.pool.HikariPool", "getConnection", "HikariPool.java", 181, null, false)))),
                new RequestInfo("POST", "https://demo.example.com/api/orders/" + random.nextInt(10_000), null,
                        Map.of("User-Agent", "loadgen")),
                Map.of("scenario", options.scenario().name().toLowerCase(java.util.Locale.ROOT)),
                List.of());
    }
}
