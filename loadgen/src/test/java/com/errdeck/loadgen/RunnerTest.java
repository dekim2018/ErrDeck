package com.errdeck.loadgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunnerTest {

    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();

    @BeforeEach
    void startFakeIngest() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // 처음 30건은 받고 그 뒤는 한도 초과로 거절하는 가짜 ingest
        server.createContext("/ingest/7/envelope", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-ErrDeck-Key"));
            body.set(exchange.getRequestBody().readAllBytes());
            int status = received.incrementAndGet() <= 30 ? 202 : 429;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 정해진_속도로_보내고_응답_코드별로_센다() throws Exception {
        Options options = Options.parse(new String[] {"burst",
                "--dsn=http://loadkey@127.0.0.1:" + server.getAddress().getPort() + "/7", "--rate=100", "--duration=1s"});
        byte[] envelope = {1, 2, 3};
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        Result result = new Runner(options, post(options, envelope), new PrintStream(log)).run();

        assertEquals(Map.of(202, 30L, 429, 70L), result.statuses());
        assertEquals(100, result.attempted());
        assertEquals(0, result.timeouts() + result.errors() + result.dropped());
        assertEquals(100, result.latency().count());
        assertEquals("loadkey", key.get());
        assertArrayEquals(envelope, body.get());
        // 1초 동안 100건이면 0.99초 근처에서 끝난다. 응답을 기다렸다가 보냈다면 더 걸린다.
        assertTrue(result.elapsed().toMillis() < 3_000, "걸린 시간 " + result.elapsed());
        assertTrue(result.format().contains("429"));
    }

    @Test
    void http_시나리오는_주소에_GET_을_보낸다() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        server.createContext("/demo/ok", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                gets.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        Options options = Options.parse(new String[] {"http",
                "--url=http://127.0.0.1:" + server.getAddress().getPort() + "/demo/ok", "--rate=50", "--duration=1s"});

        Result result = new Runner(options, Runner.requests(options), new PrintStream(new ByteArrayOutputStream())).run();

        assertEquals(Map.of(200, 50L), result.statuses());
        assertEquals(50, gets.get());
    }

    private static java.util.function.Supplier<java.net.http.HttpRequest> post(Options options, byte[] body) {
        return () -> java.net.http.HttpRequest.newBuilder(options.dsn().envelopeUri())
                .timeout(options.timeout())
                .header("X-ErrDeck-Key", options.dsn().publicKey())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    @Test
    void 응답_대기가_상한을_넘으면_보내지_않고_건수만_센다() throws Exception {
        server.removeContext("/ingest/7/envelope");
        // 응답하지 않고 붙잡는 ingest
        server.createContext("/ingest/7/envelope", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        Options options = Options.parse(new String[] {"burst",
                "--dsn=http://k@127.0.0.1:" + server.getAddress().getPort() + "/7",
                "--rate=50", "--duration=1s", "--max-in-flight=10", "--timeout=1s"});

        Result result = new Runner(options, post(options, new byte[] {1}), new PrintStream(new ByteArrayOutputStream())).run();

        assertEquals(50, result.attempted());
        assertEquals(40, result.dropped());
        assertEquals(10, result.timeouts());
    }
}
