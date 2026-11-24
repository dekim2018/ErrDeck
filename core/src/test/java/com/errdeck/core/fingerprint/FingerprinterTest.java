package com.errdeck.core.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.StackFrame;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 6.3 의 규칙 9개를 사례로 고정한다. 규칙마다 "같아야 하는 두 스택"과 "달라야 하는 두 스택"을 둔다.
 */
class FingerprinterTest {

    private static final String APP = "com.livebroadcast.interaction";
    private final Fingerprinter fingerprinter = new Fingerprinter(new AppFrames(List.of("com.livebroadcast")));

    @Nested
    class 규칙1_줄_번호와_열_번호 {

        @Test
        void 줄_번호만_다른_스택은_같은_지문이다() {
            assertSame(paymentFailure(57, 33), paymentFailure(61, 35));
        }

        @Test
        void 브라우저_프레임의_줄과_열만_다르면_같은_지문이다() {
            assertSame(
                    event(exception("TypeError", "x", new StackFrame(null, "applyRemote", "src/collab/client.ts", 10, 5, null))),
                    event(exception("TypeError", "x", new StackFrame(null, "applyRemote", "src/collab/client.ts", 99, 41, null))));
        }
    }

    @Nested
    class 규칙2_앱_프레임이_아닌_프레임 {

        @Test
        void 프레임워크_버전이_바뀌어_내부_프레임이_달라져도_같은_지문이다() {
            Event before = event(exception("java.lang.IllegalStateException", "x",
                    app("PaymentService", "charge", 57),
                    lib("org.springframework.web.servlet.FrameworkServlet", "service", 914),
                    lib("org.apache.catalina.core.ApplicationFilterChain", "doFilter", 140)));
            Event after = event(exception("java.lang.IllegalStateException", "x",
                    app("PaymentService", "charge", 57),
                    lib("org.springframework.web.servlet.FrameworkServlet", "processRequest", 1011),
                    lib("org.springframework.web.servlet.HandlerExecutionChain", "applyPreHandle", 140),
                    lib("org.apache.catalina.core.ApplicationFilterChain", "internalDoFilter", 195)));

            assertSame(before, after);
        }
    }

    @Nested
    class 규칙3_CGLIB_프록시 {

        @Test
        void 프록시_번호가_달라도_같은_지문이다() {
            assertSame(
                    event(exception("E", "x", app("PaymentService", "charge", 57), app("PaymentService$$SpringCGLIB$$0", "charge", -1))),
                    event(exception("E", "x", app("PaymentService", "charge", 57), app("PaymentService$$SpringCGLIB$$1", "charge", -1))));
        }

        @Test
        void 프록시를_거친_호출과_거치지_않은_호출이_같은_지문이다() {
            // 프록시 프레임은 원래 클래스 이름이 되어 바로 아래의 같은 프레임과 하나로 접힌다.
            assertSame(
                    event(exception("E", "x", app("PaymentService", "charge", 57), app("PaymentService$$SpringCGLIB$$0", "charge", -1))),
                    event(exception("E", "x", app("PaymentService", "charge", 57))));
        }
    }

    @Nested
    class 규칙4_JDK_프록시 {

        @Test
        void 프록시_번호와_모듈_번호가_달라도_같은_지문이다() {
            assertSame(
                    event(exception("E", "x", flagged("jdk.proxy2.$Proxy123", "findById"), app("FanService", "load", 10))),
                    event(exception("E", "x", flagged("jdk.proxy3.$Proxy45", "findById"), app("FanService", "load", 10))));
        }
    }

    @Nested
    class 규칙5_람다 {

        @Test
        void 람다_메서드의_번호와_람다_클래스의_번호와_주소가_달라도_같은_지문이다() {
            assertSame(
                    event(exception("E", "x", app("Settle$$Lambda$123/0x0000000800c0a440", "apply", -1), app("Settle", "lambda$settle$3", 20))),
                    event(exception("E", "x", app("Settle$$Lambda/0x000071c0010a5a58", "apply", -1), app("Settle", "lambda$settle$4", 21))));
        }

        @Test
        void 다른_메서드_안의_람다는_다른_지문이다() {
            assertDifferent(
                    event(exception("E", "x", app("Settle", "lambda$settle$3", 20))),
                    event(exception("E", "x", app("Settle", "lambda$refund$3", 20))));
        }
    }

    @Nested
    class 규칙6_리플렉션_접근자_프레임 {

        @Test
        void 리플렉션_프레임이_있거나_없거나_이름이_달라도_같은_지문이다() {
            Event viaGenerated = event(exception("E", "x", app("Job", "run", 10),
                    flagged("jdk.internal.reflect.GeneratedMethodAccessor12", "invoke"),
                    flagged("java.lang.reflect.Method", "invoke"),
                    app("Scheduler", "tick", 5)));
            Event viaDirect = event(exception("E", "x", app("Job", "run", 10),
                    flagged("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke"),
                    app("Scheduler", "tick", 5)));
            Event without = event(exception("E", "x", app("Job", "run", 10), app("Scheduler", "tick", 5)));

            assertSame(viaGenerated, viaDirect);
            assertSame(viaGenerated, without);
        }
    }

    @Nested
    class 규칙7_연속으로_반복되는_같은_프레임 {

        @Test
        void 재귀_깊이가_달라도_같은_지문이다() {
            assertSame(recursion(10), recursion(500));
        }

        @Test
        void 반복이_연속이_아니면_접지_않는다() {
            assertDifferent(
                    event(exception("E", "x", app("A", "f", 1), app("B", "g", 2), app("A", "f", 1))),
                    event(exception("E", "x", app("A", "f", 1), app("B", "g", 2))));
        }

        private Event recursion(int depth) {
            List<StackFrame> frames = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                frames.add(app("TreeWalker", "visit", 30));
            }
            frames.add(app("TreeWalker", "walk", 12));
            return event(new ExceptionInfo("java.lang.StackOverflowError", null, frames));
        }
    }

    @Nested
    class 규칙8_브라우저_프레임 {

        @Test
        void 같은_원본_파일이면_출처와_쿼리가_달라도_같은_지문이다() {
            assertSame(
                    event(exception("TypeError", "x", new StackFrame(null, "applyRemote", "https://copage.example.com/src/collab/client.ts?t=1", 1, 1, null))),
                    event(exception("TypeError", "x", new StackFrame(null, "applyRemote", "http://localhost:5173/src/collab/client.ts", 1, 1, null))));
        }

        @Test
        void node_modules_프레임은_재료에서_뺀다() {
            assertSame(
                    event(exception("TypeError", "x",
                            new StackFrame(null, "applyRemote", "src/collab/client.ts", 1, 1, null),
                            new StackFrame(null, "callWithErrorHandling", "node_modules/@vue/runtime-core/dist/runtime-core.esm-bundler.js", 1, 1, null))),
                    event(exception("TypeError", "x",
                            new StackFrame(null, "applyRemote", "src/collab/client.ts", 1, 1, null))));
        }

        @Test
        void 다른_원본_파일의_같은_함수는_다른_지문이다() {
            assertDifferent(
                    event(exception("TypeError", "x", new StackFrame(null, "apply", "src/collab/client.ts", 1, 1, null))),
                    event(exception("TypeError", "x", new StackFrame(null, "apply", "src/editor/adapter.ts", 1, 1, null))));
        }
    }

    @Nested
    class 규칙9_앱_프레임이_하나도_없음 {

        @Test
        void 예외_타입과_값을_지운_메시지로_대신한다() {
            Event first = event(exception("org.springframework.web.HttpMediaTypeNotSupportedException",
                    "Content-Type 'text/xml;charset=UTF-8' is not supported", lib("org.springframework.web.servlet.Dispatcher", "d", 1)));
            Event second = event(exception("org.springframework.web.HttpMediaTypeNotSupportedException",
                    "Content-Type 'application/yaml' is not supported", lib("org.springframework.web.servlet.Dispatcher", "d", 9)));

            assertSame(first, second);
            assertEquals(Fingerprint.Basis.MESSAGE, fingerprinter.fingerprint(first).basis());
        }

        @Test
        void 메시지의_문장이_다르면_다른_지문이다() {
            assertDifferent(
                    event(exception("java.io.IOException", "Connection reset by peer")),
                    event(exception("java.io.IOException", "Broken pipe")));
        }

        @Test
        void 예외_없이_보낸_메시지도_값을_지우고_묶는다() {
            assertSame(messageOnly("Payment 1024 timed out after 3000ms"), messageOnly("Payment 77 timed out after 5000ms"));
            assertDifferent(messageOnly("Payment 1024 timed out"), messageOnly("Refund 1024 timed out"));
        }
    }

    @Nested
    class 직접_지정한_지문 {

        @Test
        void SDK가_지정한_지문은_스택이_달라도_같다() {
            Event first = withCustom(paymentFailure(57, 33), List.of("payment-timeout", "toss"));
            Event second = withCustom(event(exception("java.net.SocketTimeoutException", "Read timed out", app("RefundService", "call", 9))),
                    List.of("payment-timeout", "toss"));

            assertSame(first, second);
            assertEquals(Fingerprint.Basis.CUSTOM, fingerprinter.fingerprint(first).basis());
        }

        @Test
        void 지정한_값이_다르면_다르고_규칙으로_만든_지문과도_다르다() {
            Event stack = paymentFailure(57, 33);

            assertDifferent(withCustom(stack, List.of("a")), withCustom(stack, List.of("b")));
            assertDifferent(withCustom(stack, List.of("a")), stack);
        }
    }

    @Nested
    class 다른_버그는_다른_지문 {

        @Test
        void 예외_타입이_다르면_다르다() {
            assertDifferent(
                    event(exception("java.lang.IllegalStateException", "x", app("PaymentService", "charge", 57))),
                    event(exception("java.lang.IllegalArgumentException", "x", app("PaymentService", "charge", 57))));
        }

        @Test
        void 같은_유틸_메서드에서_던져도_부른_곳이_다르면_다르다() {
            assertDifferent(
                    event(exception("E", "x", app("Validators", "requireId", 5), app("PaymentService", "charge", 57))),
                    event(exception("E", "x", app("Validators", "requireId", 5), app("FanService", "follow", 12))));
        }

        @Test
        void 원인_예외가_다르면_다르다() {
            assertDifferent(
                    event(exception("E", "x", app("PaymentService", "charge", 57)), exception("java.sql.SQLException", "y")),
                    event(exception("E", "x", app("PaymentService", "charge", 57)), exception("java.net.SocketTimeoutException", "y")));
        }

        @Test
        void 메시지만_다르고_스택이_같으면_같은_버그로_본다() {
            assertSame(
                    event(exception("E", "주문 1번 실패", app("PaymentService", "charge", 57))),
                    event(exception("E", "완전히 다른 문장", app("PaymentService", "charge", 57))));
        }
    }

    @Test
    void 지문은_소문자_16진수_32자이고_규칙_버전을_함께_돌려준다() {
        Fingerprint fingerprint = fingerprinter.fingerprint(paymentFailure(57, 33));

        assertTrue(fingerprint.value().matches("[0-9a-f]{32}"));
        assertEquals(Fingerprinter.RULE_VERSION, fingerprint.ruleVersion());
        assertEquals(Fingerprint.Basis.STACK, fingerprint.basis());
    }

    @Test
    void 같은_입력은_실행할_때마다_같은_지문이다() {
        // 규칙을 모르게 바꾸면 이 값이 바뀐다. 바꿨다면 RULE_VERSION 을 올렸는지 확인한다.
        assertEquals("e3a21adfccc565665a4817ae8edefce9", fingerprinter.fingerprint(paymentFailure(57, 33)).value());
    }

    @Test
    void 예제_봉투의_서버_에러는_SDK가_정한_in_app_으로_지문을_만든다() {
        Event event = Event.fromJson(new EnvelopeCodec(EnvelopeLimits.DEFAULT)
                .read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE)).items().get(0).payload());

        List<String> keys = fingerprinter.appFrameKeys(event.exceptions().get(0).frames());

        assertEquals(List.of(
                "com.livebroadcast.interaction.payment.PaymentService#charge",
                "com.livebroadcast.interaction.payment.PaymentController#lambda$pay"), keys);
    }

    @Test
    void 맨_위_앱_프레임을_이슈의_발생_위치로_돌려준다() {
        assertEquals("com.livebroadcast.interaction.payment.PaymentService#charge", fingerprinter.culprit(paymentFailure(57, 33)));
        assertEquals(null, fingerprinter.culprit(messageOnly("앱 프레임 없음")));
    }

    private Event paymentFailure(int chargeLine, int controllerLine) {
        return event(
                exception("org.springframework.dao.DataAccessResourceFailureException", "Unable to acquire JDBC Connection",
                        lib("com.zaxxer.hikari.pool.HikariPool", "getConnection", 181),
                        app("payment.PaymentService$$SpringCGLIB$$0", "charge", -1),
                        app("payment.PaymentService", "charge", chargeLine),
                        app("payment.PaymentController", "lambda$pay$0", controllerLine),
                        lib("org.springframework.web.servlet.FrameworkServlet", "service", 914)),
                exception("java.sql.SQLTransientConnectionException", "HikariPool-1 - Connection is not available",
                        lib("com.zaxxer.hikari.pool.HikariPool", "createTimeoutException", 696)));
    }

    private static StackFrame app(String simpleName, String method, int line) {
        return new StackFrame(APP + "." + simpleName, method, simpleName + ".java", line < 0 ? null : line, null, null);
    }

    private static StackFrame lib(String className, String method, int line) {
        return new StackFrame(className, method, null, line, null, null);
    }

    /** 패키지 목록에 없지만 SDK가 앱 프레임으로 표시한 프레임. */
    private static StackFrame flagged(String className, String method) {
        return new StackFrame(className, method, null, null, null, true);
    }

    private static ExceptionInfo exception(String type, String value, StackFrame... frames) {
        return new ExceptionInfo(type, value, List.of(frames));
    }

    private static Event event(ExceptionInfo... chain) {
        return new Event(UUID.randomUUID(), Instant.now(), "error", null, "prod", "r1", null, null,
                List.of(), 1, List.of(chain), null, Map.of(), List.of());
    }

    private static Event messageOnly(String message) {
        return new Event(UUID.randomUUID(), Instant.now(), "error", message, "prod", "r1", null, null,
                List.of(), 1, List.of(), null, Map.of(), List.of());
    }

    private static Event withCustom(Event event, List<String> fingerprint) {
        return new Event(event.eventId(), event.timestamp(), event.level(), event.message(), event.environment(), event.release(),
                event.userId(), event.traceId(), fingerprint, event.count(), event.exceptions(), event.request(), event.tags(),
                event.breadcrumbs());
    }

    private void assertSame(Event first, Event second) {
        assertEquals(fingerprinter.fingerprint(first), fingerprinter.fingerprint(second));
    }

    private void assertDifferent(Event first, Event second) {
        assertNotEquals(fingerprinter.fingerprint(first).value(), fingerprinter.fingerprint(second).value());
    }
}
