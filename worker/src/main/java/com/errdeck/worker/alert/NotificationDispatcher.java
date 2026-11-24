package com.errdeck.worker.alert;

import com.errdeck.core.json.Json;
import com.errdeck.core.net.UrlGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 알림 발송(16.3). {@code errdeck:stream:notify} 를 소비 그룹으로 읽어 가상 스레드로 보낸다. 동시 발송 10건, 제한 시간 5초,
 * 실패하면 간격을 늘려 3번까지 다시 보내고 끝내 실패하면 기록에 남긴다. 판정과 발송을 나눠, 외부 채널이 느려도 이벤트 처리가
 * 늦어지지 않는다.
 */
public class NotificationDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final String GROUP = "errdeck-notifiers";
    private static final int ATTEMPTS = 3;
    /** 채널(규칙)마다 분당 상한(16.2 넷째 줄). 넘는 것은 보내지 않고 기록만 남긴다. */
    private static final int PER_MINUTE = 20;

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final UrlGuard guard;
    private final MeterRegistry registry;
    private final Clock clock;
    private final String consumer;
    private final Semaphore slots = new Semaphore(10);
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private volatile boolean running;
    private Thread reader;

    public NotificationDispatcher(JdbcClient jdbc, StringRedisTemplate redis, UrlGuard guard, MeterRegistry registry, Clock clock,
                                  String consumer) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.guard = guard;
        this.registry = registry;
        this.clock = clock;
        this.consumer = consumer;
    }

    @Override
    public void start() {
        try {
            redis.opsForStream().createGroup(AlertEngine.STREAM, ReadOffset.from("0"), GROUP);
        } catch (RedisSystemException e) {
            // 이미 있다(BUSYGROUP).
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP") && !String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
        running = true;
        reader = Thread.ofPlatform().name("errdeck-notify-reader").daemon().start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (reader != null) {
            reader.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @SuppressWarnings("unchecked")
    private void loop() {
        long nextClaim = 0;
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records;
                if (System.currentTimeMillis() >= nextClaim) {
                    nextClaim = System.currentTimeMillis() + 30_000;
                    // 죽은 worker 가 꺼내 놓고 보내지 못한 것을 가져온다.
                    var stale = pendingIds();
                    records = stale.length == 0 ? List.of() : (List<MapRecord<String, Object, Object>>) (List<?>)
                            redis.opsForStream().claim(AlertEngine.STREAM, GROUP, consumer, Duration.ofSeconds(60), stale);
                } else {
                    records = redis.opsForStream().read(Consumer.from(GROUP, consumer),
                            StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                            StreamOffset.create(AlertEngine.STREAM, ReadOffset.lastConsumed()));
                }
                if (records == null) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    slots.acquire();
                    Thread.ofVirtual().name("errdeck-notify").start(() -> {
                        try {
                            deliver(Long.parseLong(String.valueOf(record.getValue().get("id"))));
                            redis.opsForStream().acknowledge(AlertEngine.STREAM, GROUP, record.getId());
                            redis.opsForStream().delete(AlertEngine.STREAM, record.getId());
                        } catch (RuntimeException e) {
                            log.warn("알림 {} 을 처리하지 못했다: {}", record.getId(), e.toString());
                        } finally {
                            slots.release();
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("알림 대기열을 읽지 못했다: {}", e.toString());
                    sleep();
                }
            }
        }
    }

    private org.springframework.data.redis.connection.stream.RecordId[] pendingIds() {
        var pending = redis.opsForStream().pending(AlertEngine.STREAM, GROUP, org.springframework.data.domain.Range.unbounded(), 100);
        return pending.stream()
                .filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofSeconds(60)) > 0)
                .map(org.springframework.data.redis.connection.stream.PendingMessage::getId)
                .toArray(org.springframework.data.redis.connection.stream.RecordId[]::new);
    }

    record Pending(long id, long ruleId, String channel, String target, String title, String body, String link, String status) {
    }

    void deliver(long id) {
        Pending n = jdbc.sql("""
                        SELECT n.id, n.rule_id, r.channel, r.target, n.title, n.body, n.link, n.status
                        FROM notifications n JOIN alert_rules r ON r.id = n.rule_id WHERE n.id = ?
                        """)
                .param(id)
                .query((rs, i) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)))
                .optional()
                .orElse(null);
        if (n == null || !"pending".equals(n.status())) {
            return;
        }
        String minuteKey = "errdeck:notify:rate:" + n.ruleId() + ":" + clock.instant().getEpochSecond() / 60;
        Long sent = redis.opsForValue().increment(minuteKey);
        redis.expire(minuteKey, Duration.ofMinutes(2));
        if (sent != null && sent > PER_MINUTE) {
            finish(n, "failed", 0, "채널의 분당 상한(" + PER_MINUTE + "건)을 넘어 보내지 않았다");
            return;
        }
        String error = null;
        int attempt = 0;
        while (attempt < ATTEMPTS) {
            attempt++;
            try {
                error = send(n);
                if (error == null) {
                    finish(n, "sent", attempt, null);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "중단되었다";
                break;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        finish(n, "failed", attempt, error);
    }

    /** @return 실패 이유. 보냈으면 null */
    private String send(Pending n) throws Exception {
        if ("email".equals(n.channel())) {
            return "이메일 채널은 아직 없다";
        }
        URI uri = guard.check(n.target());
        String text = "*" + n.title() + "*" + (n.body() == null || n.body().isBlank() ? "" : "\n" + n.body())
                + (n.link() == null ? "" : "\n" + n.link());
        // Slack 은 text, Discord 는 content 를 읽는다. 둘 다 2,000자 안팎이 상한이다.
        String trimmed = text.length() > 1900 ? text.substring(0, 1899) + "…" : text;
        Map<String, Object> payload = "discord".equals(n.channel()) ? Map.of("content", trimmed) : Map.of("text", trimmed);
        HttpResponse<Void> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                .build(), HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        return status / 100 == 2 ? null : "채널이 " + status + " 로 답했다";
    }

    private void finish(Pending n, String status, int attempts, String error) {
        jdbc.sql("UPDATE notifications SET status = ?, attempts = ?, last_error = ?, sent_at = ? WHERE id = ?")
                .params(status, attempts, error, "sent".equals(status) ? Timestamp.from(clock.instant()) : null, n.id())
                .update();
        registry.counter("errdeck.notifications", "channel", n.channel(), "result", status).increment();
        if (error != null) {
            log.warn("알림 {} 을 보내지 못했다: {}", n.id(), error);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
