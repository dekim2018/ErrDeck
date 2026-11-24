package com.errdeck.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.dsn.Dsn;
import com.errdeck.core.event.Event;
import com.errdeck.core.event.StackFrame;
import com.errdeck.sdk.support.FakeIngest;
import com.errdeck.sdk.support.FakeIngest.Reply;
import com.errdeck.sdk.transport.DiscardReason;
import com.errdeck.sdk.transport.HttpTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 가짜 ingest 를 상대로 큐 넘침, 거절 응답, 종료 시 비우기를 확인한다(09 작업). */
class ErrDeckClientTest {

    private final FakeIngest ingest = new FakeIngest();
    private ErrDeckClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        ingest.close();
    }

    @Test
    void 잡은_예외를_사슬과_앱_프레임과_지운_요청_정보와_함께_보낸다() {
        client = client(options(100, 50, Duration.ofMillis(100), 1000));
        RequestContext request = new RequestContext("POST", "http://app/pay", "a=1&token=xyz",
                Map.of("Authorization", "Bearer secret", "X-Api-Key", "k", "Accept", "*/*"), "user-1");

        client.capture(new IllegalStateException("결제 실패", new java.io.IOException("연결 끊김")), request);

        await().atMost(Duration.ofSeconds(5)).until(() -> !ingest.events().isEmpty());
        Event event = ingest.events().getFirst();
        assertThat(event.exceptions()).extracting(e -> e.type()).containsExactly("java.lang.IllegalStateException", "java.io.IOException");
        assertThat(event.exceptions().getFirst().value()).isEqualTo("결제 실패");
        StackFrame top = event.exceptions().getFirst().frames().getFirst();
        assertThat(top.module()).isEqualTo(ErrDeckClientTest.class.getName());
        assertThat(top.inApp()).isTrue();
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.environment()).isEqualTo("test");
        assertThat(event.request().headers()).containsEntry("Authorization", "[Filtered]").containsEntry("X-Api-Key", "[Filtered]")
                .containsEntry("Accept", "*/*");
        assertThat(event.request().queryString()).isEqualTo("a=1&token=[Filtered]");
        assertThat(ingest.keys).contains("testkey");
    }

    @Test
    void 큐가_차면_기다리지_않고_버리고_센_건수를_다음에_client_report_로_보낸다() {
        // 첫 전송을 2초 붙잡아 전송 스레드가 큐를 비우지 못하게 한다.
        ingest.reply(n -> n == 1 ? new Reply(202, null, 2_000) : Reply.accepted());
        client = client(options(10, 5, Duration.ofMillis(50), 1000));

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            client.capture(distinct(i), null);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        assertThat(client.discards().get(DiscardReason.QUEUE_OVERFLOW)).isGreaterThan(0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(ingest.eventCount() + ingest.discarded("queue_overflow")).isEqualTo(100));
    }

    @Test
    void 응답_429를_받으면_Retry_After_동안_보내지_않고_그동안의_건수를_센다() throws Exception {
        ingest.reply(n -> n == 1 ? new Reply(429, 1L, 0) : Reply.accepted());
        client = client(options(100, 50, Duration.ofMillis(100), 1000));

        client.capture(distinct(0), null);
        await().atMost(Duration.ofSeconds(5)).until(() -> client.discards().get(DiscardReason.RATE_LIMITED) == 1);
        client.capture(distinct(1), null);
        Thread.sleep(300);
        assertThat(ingest.envelopes).isEmpty();

        Thread.sleep(1_000);
        client.capture(distinct(2), null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(ingest.events()).hasSize(1);
            assertThat(ingest.discarded("rate_limited")).isEqualTo(2);
        });
    }

    @Test
    void 응답_503도_429와_같이_쉬고_대기열이_찼다는_이유로_센다() {
        ingest.reply(n -> n == 1 ? new Reply(503, 1L, 0) : Reply.accepted());
        client = client(options(100, 50, Duration.ofMillis(100), 1000));

        client.capture(distinct(0), null);
        await().atMost(Duration.ofSeconds(5)).until(() -> client.discards().get(DiscardReason.QUEUE_FULL) == 1);
    }

    @Test
    void 키가_틀려_401을_받으면_전송을_멈춘다() throws Exception {
        ingest.reply(n -> new Reply(401, null, 0));
        client = client(options(100, 50, Duration.ofMillis(100), 1000));

        client.capture(distinct(0), null);
        await().atMost(Duration.ofSeconds(5)).until(() -> client.discards().get(DiscardReason.UNAUTHORIZED) == 1);
        client.capture(distinct(1), null);
        Thread.sleep(500);

        assertThat(client.discards().get(DiscardReason.UNAUTHORIZED)).isEqualTo(2);
    }

    @Test
    void 종료할_때_큐에_남은_것을_보내고_2초_안에_끝난다() {
        client = client(options(1000, 50, Duration.ofSeconds(30), 1000));
        for (int i = 0; i < 30; i++) {
            client.capture(distinct(i), null);
        }

        long start = System.nanoTime();
        client.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        client = null;

        assertThat(ingest.events()).hasSize(30);
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void ingest가_없어도_요청_스레드는_막히지_않고_종료도_제한_시간_안에_끝난다() {
        ingest.close();
        client = client(options(1000, 50, Duration.ofMillis(50), 1000));

        long start = System.nanoTime();
        for (int i = 0; i < 5_000; i++) {
            client.capture(distinct(i), null);
        }
        Duration captureTime = Duration.ofNanos(System.nanoTime() - start);
        long closeStart = System.nanoTime();
        client.close();
        Duration closeTime = Duration.ofNanos(System.nanoTime() - closeStart);
        client = null;

        assertThat(captureTime).isLessThan(Duration.ofMillis(500));
        assertThat(closeTime).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void 같은_에러가_구간_안에_기준을_넘으면_대표_한_건에_횟수를_실어_보낸다() {
        client = client(new ErrDeckOptions(Dsn.parse(ingest.dsn()), "test", "r1", List.of("com.errdeck"), 1000, 50,
                Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(300), 5));

        for (int i = 0; i < 20; i++) {
            client.capture(sameError(), null);
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(ingest.events()).hasSize(6);
            assertThat(ingest.eventCount()).isEqualTo(20);
        });
        assertThat(ingest.events()).extracting(Event::count).containsOnly(1L, 15L);
    }

    @Test
    void 같은_예외_객체를_두_번_잡아도_한_번만_보낸다() {
        client = client(options(100, 50, Duration.ofMillis(100), 1000));
        IllegalStateException error = new IllegalStateException("한 번");

        client.capture(error, null);
        client.capture(error, null);

        await().atMost(Duration.ofSeconds(5)).until(() -> !ingest.events().isEmpty());
        assertThat(ingest.events()).hasSize(1);
    }

    @Test
    void 잘못된_입력에도_예외를_던지지_않는다() {
        client = client(options(100, 50, Duration.ofMillis(100), 1000));

        client.capture(null, null);
        client.capture(new Throwable() {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("스택을 줄 수 없다");
            }
        }, null);
    }

    private ErrDeckClient client(ErrDeckOptions options) {
        return new ErrDeckClient(options, new HttpTransport(options.dsn(), options.timeout()), Clock.systemUTC());
    }

    private ErrDeckOptions options(int queue, int batch, Duration interval, int foldThreshold) {
        return new ErrDeckOptions(Dsn.parse(ingest.dsn()), "test", "r1", List.of("com.errdeck"), queue, batch, interval,
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(10), foldThreshold);
    }

    /** 스택이 같은 예외. 같은 줄에서 만든다. */
    private static RuntimeException sameError() {
        return new IllegalStateException("같은 에러");
    }

    /** 접기에 걸리지 않게 타입이 다른 예외를 만든다. 스택은 같으므로 접기 기준을 크게 둔 옵션과 함께 쓴다. */
    private static RuntimeException distinct(int i) {
        return new IllegalStateException("에러 " + i);
    }
}
