package com.errdeck.worker.uptime;

import com.errdeck.worker.alert.AlertEngine;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 업타임 점검의 실행 구조(9.2). 1초마다 Lua 스크립트로 때가 된 모니터를 꺼내 가상 스레드로 점검한다. 동시 점검은 세마포어 50.
 * 결과는 모아서 ClickHouse 에 쓰고, 상태가 바뀌면 장애 기록을 고치고 알린다.
 */
public class UptimeRunner implements SmartLifecycle {

    static final String DUE = "errdeck:monitors:due";
    static final String INTERVALS = "errdeck:monitors:interval";
    private static final Logger log = LoggerFactory.getLogger(UptimeRunner.class);

    private final MonitorStore store;
    private final UptimeChecker checker;
    private final UptimeWriter writer;
    private final AlertEngine alerts;
    private final StringRedisTemplate redis;
    private final UptimeProperties properties;
    private final Clock clock;
    private final Semaphore slots;
    private final DistributionSummary lag;
    private final Timer checkTime;
    private final DefaultRedisScript<List> dueScript;
    private final Map<Long, Monitor> monitors = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CheckResult> results = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private Thread ticker;

    public UptimeRunner(MonitorStore store, UptimeChecker checker, UptimeWriter writer, AlertEngine alerts, StringRedisTemplate redis,
                        UptimeProperties properties, MeterRegistry registry, Clock clock) {
        this.store = store;
        this.checker = checker;
        this.writer = writer;
        this.alerts = alerts;
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.slots = new Semaphore(properties.concurrency());
        // 예정 시각과 실제 시각의 차이(13.4). 클수록 점검이 밀리고 있다.
        this.lag = DistributionSummary.builder("errdeck.uptime.schedule.lag").baseUnit("seconds").publishPercentileHistogram()
                .register(registry);
        this.checkTime = Timer.builder("errdeck.uptime.check").register(registry);
        this.dueScript = new DefaultRedisScript<>();
        dueScript.setLocation(new ClassPathResource("redis/monitors_due.lua"));
        dueScript.setResultType(List.class);
    }

    @Override
    public void start() {
        running = true;
        ticker = Thread.ofPlatform().name("errdeck-uptime").daemon().start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (ticker != null) {
            ticker.interrupt();
        }
        flush();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        Instant nextSync = Instant.EPOCH;
        Instant nextFlush = clock.instant().plusSeconds(5);
        while (running) {
            try {
                Instant now = clock.instant();
                if (!now.isBefore(nextSync)) {
                    sync(now);
                    nextSync = now.plus(properties.syncInterval());
                }
                runDue(now);
                if (!now.isBefore(nextFlush)) {
                    flush();
                    nextFlush = now.plusSeconds(5);
                }
                Thread.sleep(properties.tick().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("업타임 점검 일정을 돌리지 못했다: {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * PostgreSQL 의 모니터를 일정에 맞춘다. 새 모니터의 첫 실행 시각은 ID 의 해시로 주기 안에 흩는다(9.2). 지운 모니터는 뺀다.
     * worker 가 여러 대여도 ZADD NX 라 일정은 하나다.
     */
    void sync(Instant now) {
        List<Monitor> all = store.all();
        Set<String> ids = new HashSet<>();
        Map<String, String> intervals = new HashMap<>();
        monitors.clear();
        for (Monitor m : all) {
            monitors.put(m.id(), m);
            ids.add(Long.toString(m.id()));
            intervals.put(Long.toString(m.id()), Integer.toString(m.intervalSeconds()));
            long offset = Math.floorMod(Long.hashCode(m.id() * 0x9E3779B97F4A7C15L), m.intervalSeconds() * 1000L);
            redis.opsForZSet().addIfAbsent(DUE, Long.toString(m.id()), now.toEpochMilli() + offset);
        }
        if (!intervals.isEmpty()) {
            redis.opsForHash().putAll(INTERVALS, intervals);
        }
        Set<String> scheduled = redis.opsForZSet().range(DUE, 0, -1);
        if (scheduled != null) {
            for (String id : scheduled) {
                if (!ids.contains(id)) {
                    redis.opsForZSet().remove(DUE, id);
                    redis.opsForHash().delete(INTERVALS, id);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runDue(Instant now) throws InterruptedException {
        List<Object> due = redis.execute(dueScript, List.of(DUE, INTERVALS), Long.toString(now.toEpochMilli()), "200");
        if (due == null) {
            return;
        }
        for (int i = 0; i + 1 < due.size(); i += 2) {
            long id = Long.parseLong(String.valueOf(due.get(i)));
            double scheduledAt = Double.parseDouble(String.valueOf(due.get(i + 1)));
            Monitor monitor = monitors.get(id);
            if (monitor == null) {
                continue;
            }
            if (monitor.pausedUntil() != null && monitor.pausedUntil().isAfter(now)) {
                // 중지한 동안은 점검하지 않는다. 가동률 계산에서도 빠진다(9.1).
                continue;
            }
            lag.record(Math.max(0, (now.toEpochMilli() - scheduledAt) / 1000.0));
            if (!slots.tryAcquire(1, TimeUnit.SECONDS)) {
                // 자리가 없으면 다음 바퀴에 다시 꺼내도록 일정을 앞당긴다.
                redis.opsForZSet().add(DUE, Long.toString(id), now.toEpochMilli() + 1000);
                continue;
            }
            Thread.ofVirtual().name("errdeck-uptime-check").start(() -> {
                try {
                    handle(monitor);
                } catch (RuntimeException e) {
                    log.warn("모니터 {} 점검을 처리하지 못했다: {}", monitor.id(), e.toString());
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void handle(Monitor monitor) {
        CheckResult result = checkTime.record(() -> checker.check(monitor));
        results.add(result);
        MonitorStore.Outcome outcome = store.apply(monitor, result);
        switch (outcome.transition()) {
            case RETRY -> // 판정이 주기만큼 늦어지지 않게 10초 뒤 다시 본다(9.3).
                    redis.opsForZSet().add(DUE, Long.toString(monitor.id()), clock.instant().plus(properties.retryDelay()).toEpochMilli());
            case WENT_DOWN -> wentDown(monitor, result);
            case RECOVERED -> alerts.fire(monitor.projectId(), "monitor_down", "up:" + monitor.id() + ":" + result.timestamp().getEpochSecond(),
                    "복구: " + monitor.label(), "다시 응답한다", "/projects/" + monitor.projectId() + "/monitors");
            case NONE -> { }
        }
        certificate(monitor, result);
    }

    /** 모니터의 절반 이상이 동시에 다운이면 점검기 쪽 문제로 보고 개별 알림 대신 한 건만 보낸다(9.5). */
    private void wentDown(Monitor monitor, CheckResult result) {
        int[] counts = store.downAndActive(clock.instant());
        if (counts[1] >= 4 && counts[0] * 2 >= counts[1]) {
            long bucket = clock.instant().getEpochSecond() / 1800;
            alerts.fire(monitor.projectId(), "monitor_down", "checker:" + bucket, "점검기 이상 의심",
                    "모니터 " + counts[1] + "개 중 " + counts[0] + "개가 동시에 다운이다. 이 서버의 네트워크를 먼저 확인한다", null);
            return;
        }
        alerts.fire(monitor.projectId(), "monitor_down", "down:" + monitor.id() + ":" + result.timestamp().getEpochSecond(),
                "다운: " + monitor.label(), result.error(), "/projects/" + monitor.projectId() + "/monitors");
    }

    private void certificate(Monitor monitor, CheckResult result) {
        if (result.certExpiresAt() == null) {
            return;
        }
        Duration left = Duration.between(clock.instant(), result.certExpiresAt());
        if (left.compareTo(properties.certWarning()) <= 0) {
            // 하루에 한 번만.
            alerts.fire(monitor.projectId(), "cert_expiry", "cert:" + monitor.id() + ":" + clock.instant().getEpochSecond() / 86400,
                    "인증서 만료 임박: " + monitor.label(), "만료까지 " + Math.max(0, left.toDays()) + "일 (" + result.certExpiresAt() + ")",
                    "/projects/" + monitor.projectId() + "/monitors");
        }
    }

    private void flush() {
        List<CheckResult> batch = new ArrayList<>();
        CheckResult r;
        while ((r = results.poll()) != null) {
            batch.add(r);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            writer.write(batch);
        } catch (RuntimeException e) {
            // 점검 결과는 판정에 이미 반영했다. 가동률 계산의 행만 잃는다.
            log.warn("점검 결과 {}건을 쓰지 못했다: {}", batch.size(), e.toString());
        }
    }

    /** uptime_checks 에 쓴다. */
    public interface UptimeWriter {
        void write(List<CheckResult> results);
    }
}
