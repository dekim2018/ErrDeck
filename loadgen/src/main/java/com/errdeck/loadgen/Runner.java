package com.errdeck.loadgen;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * 정해진 속도로 봉투를 보낸다.
 *
 * <p>응답을 기다렸다가 다음 요청을 보내지 않는다(열린 부하). ingest 가 느려져도 보내는 속도는 그대로여야
 * 감시 대상의 장애로 에러가 쏟아지는 상황(5.1)과 같아진다. 그래서 응답 시간도 실제로 보낸 시각이 아니라
 * 보내기로 한 시각부터 잰다. 도구가 밀려 늦게 보낸 만큼 응답 시간이 길게 잡히므로 결과를 좋게 왜곡하지 않는다.
 */
final class Runner {

    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final Options options;
    private final Supplier<HttpRequest> requests;
    private final PrintStream out;

    Runner(Options options, Supplier<HttpRequest> requests, PrintStream out) {
        this.options = options;
        this.requests = requests;
        this.out = out;
    }

    Result run() throws InterruptedException {
        Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
        LongAdder timeouts = new LongAdder();
        LongAdder errors = new LongAdder();
        Map<String, LongAdder> errorKinds = new ConcurrentHashMap<>();
        LongAdder dropped = new LongAdder();
        AtomicInteger inFlight = new AtomicInteger();
        LatencyHistogram latency = new LatencyHistogram();

        long total = Math.round(options.duration().toNanos() / 1e9 * options.envelopesPerSecond());
        long intervalNanos = Math.round(1e9 / options.envelopesPerSecond());

        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(options.timeout())
                .build();
             ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            long nextProgress = start + PROGRESS_INTERVAL.toNanos();
            for (long i = 0; i < total; i++) {
                long intended = start + i * intervalNanos;
                waitUntil(intended);
                if (System.nanoTime() >= nextProgress) {
                    progress(start, i, statuses, dropped);
                    nextProgress += PROGRESS_INTERVAL.toNanos();
                }
                if (inFlight.incrementAndGet() > options.maxInFlight()) {
                    inFlight.decrementAndGet();
                    dropped.increment();
                    continue;
                }
                senders.execute(() -> {
                    try {
                        HttpRequest request = requests.get();
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        latency.record((System.nanoTime() - intended) / 1000);
                        statuses.computeIfAbsent(response.statusCode(), s -> new LongAdder()).increment();
                    } catch (HttpTimeoutException e) {
                        timeouts.increment();
                    } catch (IOException e) {
                        errors.increment();
                        errorKinds.computeIfAbsent(describe(e), k -> new LongAdder()).increment();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
            senders.shutdown();
            senders.awaitTermination(options.timeout().toMillis() + 5_000, TimeUnit.MILLISECONDS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            Map<Integer, Long> counts = new ConcurrentHashMap<>();
            statuses.forEach((status, count) -> counts.put(status, count.sum()));
            Map<String, Long> kinds = new ConcurrentHashMap<>();
            errorKinds.forEach((kind, count) -> kinds.put(kind, count.sum()));
            return new Result(options, elapsed, counts, timeouts.sum(), errors.sum(), kinds, dropped.sum(), latency);
        }
    }

    private void progress(long start, long scheduled, Map<Integer, LongAdder> statuses, LongAdder dropped) {
        StringBuilder line = new StringBuilder(String.format("[%5.1fs] 예정 %,d", (System.nanoTime() - start) / 1e9, scheduled));
        statuses.forEach((status, count) -> line.append(String.format(", %d=%,d", status, count.sum())));
        if (dropped.sum() > 0) {
            line.append(String.format(", 보내지 않음=%,d", dropped.sum()));
        }
        out.println(line);
    }

    /** 연결 실패의 원인을 묶어 센다. 도구 쪽 한계인지 ingest 쪽 거절인지 가리려고 가장 안쪽 원인까지 본다. */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : ": " + root.getMessage();
        return root.getClass().getSimpleName() + message;
    }

    private static void waitUntil(long nanoTime) {
        long remaining;
        while ((remaining = nanoTime - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    /** 시나리오에 맞는 요청을 만든다. 봉투는 보내기 직전에 만들어 매번 다른 event_id 를 쓴다. */
    static Supplier<HttpRequest> requests(Options options) {
        if (options.scenario() == Scenario.HTTP) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(options.url()).timeout(options.timeout()).GET();
            if (options.bearer() != null) {
                builder.header("Authorization", "Bearer " + options.bearer());
            }
            HttpRequest get = builder.build();
            return () -> get;
        }
        EventFactory factory = new EventFactory(options, Clock.systemUTC());
        URI uri = options.dsn().envelopeUri();
        return () -> HttpRequest.newBuilder(uri)
                .timeout(options.timeout())
                .header("X-ErrDeck-Key", options.dsn().publicKey())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(factory.nextEnvelope()))
                .build();
    }
}
