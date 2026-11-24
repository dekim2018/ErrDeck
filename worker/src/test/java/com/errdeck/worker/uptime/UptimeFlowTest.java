package com.errdeck.worker.uptime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.worker.support.TestInfra;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 업타임 점검부터 알림 발송까지(9장, 16장). 가짜 서버 하나가 감시 대상과 Discord 웹훅 노릇을 한다.
 * 연속 2회 실패로 다운, 장애 기록, 알림. 연속 2회 성공으로 복구, 장애 기록 닫힘, 복구 알림.
 */
@SpringBootTest(properties = {
        "errdeck.worker.compute-threads=1",
        "errdeck.uptime.allowed-hosts=127.0.0.1",
        "errdeck.uptime.tick=100ms",
        "errdeck.uptime.retry-delay=200ms",
        "errdeck.uptime.sync-interval=1h"})
class UptimeFlowTest extends TestInfra {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    UptimeRunner runner;

    HttpServer server;
    final AtomicInteger status = new AtomicInteger(500);
    final ConcurrentLinkedQueue<String> webhooks = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.createContext("/hook", exchange -> {
            webhooks.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 연속_2회_실패하면_다운이고_연속_2회_성공하면_복구한다() {
        long projectId = createProject(jdbc);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        long monitorId = jdbc.sql("INSERT INTO monitors (project_id, name, url, interval_seconds) VALUES (?, '데모', ?, 30) RETURNING id")
                .params(projectId, base + "/health").query(Long.class).single();
        jdbc.sql("INSERT INTO alert_rules (project_id, kind, channel, target) VALUES (?, 'monitor_down', 'discord', ?)")
                .params(projectId, base + "/hook").update();

        runner.sync(Clock.systemUTC().instant());
        redis.opsForZSet().add(UptimeRunner.DUE, Long.toString(monitorId), System.currentTimeMillis());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(status(monitorId)).isEqualTo("down");
            assertThat(webhooks).anyMatch(b -> b.contains("다운: 데모") && b.contains("상태 코드 500"));
        });
        assertThat(openIncidents(monitorId)).isEqualTo(1);

        status.set(200);
        redis.opsForZSet().add(UptimeRunner.DUE, Long.toString(monitorId), System.currentTimeMillis());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(status(monitorId)).isEqualTo("up");
            assertThat(webhooks).anyMatch(b -> b.contains("복구: 데모"));
        });
        assertThat(openIncidents(monitorId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE status = 'sent'").query(Long.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 두_worker_가_동시에_꺼내도_같은_모니터를_두_번_꺼내지_않는다() throws Exception {
        String due = "errdeck:test:due:" + System.nanoTime();
        String intervals = "errdeck:test:interval:" + System.nanoTime();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 500; i++) {
            redis.opsForZSet().add(due, Integer.toString(i), now - i);
        }
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/monitors_due.lua"));
        script.setResultType(List.class);

        List<Object> all = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<Object>>>();
            for (int w = 0; w < 4; w++) {
                futures.add(executor.submit(() -> {
                    List<Object> mine = new ArrayList<>();
                    for (int round = 0; round < 10; round++) {
                        @SuppressWarnings("unchecked")
                        List<Object> got = redis.execute(script, List.of(due, intervals), Long.toString(now), "50");
                        for (int i = 0; i < got.size(); i += 2) {
                            mine.add(got.get(i));
                        }
                    }
                    return mine;
                }));
            }
            for (var f : futures) {
                all.addAll(f.get());
            }
        }
        Set<Object> unique = new HashSet<>(all);
        assertThat(all).hasSize(500);
        assertThat(unique).hasSize(500);
    }

    private String status(long monitorId) {
        return jdbc.sql("SELECT status FROM monitors WHERE id = ?").param(monitorId).query(String.class).single();
    }

    private long openIncidents(long monitorId) {
        return jdbc.sql("SELECT count(*) FROM monitor_incidents WHERE monitor_id = ? AND ended_at IS NULL").param(monitorId)
                .query(Long.class).single();
    }
}
