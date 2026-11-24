package com.errdeck.sdk.support;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.ClientReport;
import com.errdeck.core.event.Event;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 봉투를 받아 풀어 두는 가짜 ingest. 응답 코드와 지연을 바꿀 수 있다. */
public final class FakeIngest implements AutoCloseable {

    public record Reply(int status, Long retryAfter, long delayMillis) {

        public static Reply accepted() {
            return new Reply(202, null, 0);
        }
    }

    private final HttpServer server;
    private final EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
    public final ConcurrentLinkedQueue<Envelope> envelopes = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<String> keys = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Function<Integer, Reply>> replies = new AtomicReference<>(n -> Reply.accepted());
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    public FakeIngest() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ingest/1/envelope", exchange -> {
            int n = requests.incrementAndGet();
            Reply reply = replies.get().apply(n);
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (reply.status() / 100 == 2) {
                envelopes.add(codec.decode(body));
                keys.add(exchange.getRequestHeaders().getFirst("X-ErrDeck-Key"));
            }
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", Long.toString(reply.retryAfter()));
            }
            exchange.sendResponseHeaders(reply.status(), -1);
            exchange.close();
        });
        server.start();
    }

    /** n 번째 요청(1부터)에 줄 응답. */
    public void reply(Function<Integer, Reply> replies) {
        this.replies.set(replies);
    }

    public String dsn() {
        return "http://testkey@127.0.0.1:" + server.getAddress().getPort() + "/1";
    }

    public List<Event> events() {
        return envelopes.stream().flatMap(e -> e.items().stream())
                .filter(item -> item.type() == ItemType.EVENT)
                .map(EnvelopeItem::payload)
                .map(Event::fromJson)
                .toList();
    }

    /** 받은 이벤트가 대표하는 발생 횟수의 합. 접힌 것은 count 로 센다. */
    public long eventCount() {
        return events().stream().mapToLong(Event::count).sum();
    }

    public long discarded(String reason) {
        return envelopes.stream().flatMap(e -> e.items().stream())
                .filter(item -> item.type() == ItemType.CLIENT_REPORT)
                .map(item -> ClientReport.fromJson(item.payload()))
                .flatMap(report -> report.discarded().stream())
                .filter(d -> d.reason().equals(reason))
                .mapToLong(ClientReport.Discarded::quantity)
                .sum();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
