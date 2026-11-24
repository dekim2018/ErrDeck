package com.errdeck.worker.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.worker.support.TestInfra;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 알림이 폭주하지 않는지(16.2). 같은 키는 한 번만, worker 여러 대가 동시에 판정해도 한 번만. */
@SpringBootTest(properties = {"errdeck.worker.compute-threads=1", "errdeck.uptime.enabled=false"})
class AlertEngineTest extends TestInfra {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void 같은_이슈의_알림은_재알림_간격_안에서_한_번만_연다() {
        long projectId = createProject(jdbc);
        rule(projectId, "frequency");
        AlertEngine engine = new AlertEngine(jdbc, redis, (p, w, t) -> Map.of(), Clock.systemUTC(), "http://console");
        AlertEngine.Rule rule = engine.rules(projectId, "frequency").getFirst();

        int opened = 0;
        for (int i = 0; i < 50; i++) {
            opened += engine.open(rule, "freq:42:" + engine.bucket(rule), "frequency", "빈도 초과", "", "/issues/42") ? 1 : 0;
        }

        assertThat(opened).isEqualTo(1);
        assertThat(count(projectId)).isEqualTo(1);
    }

    @Test
    void worker_여러_대가_동시에_판정해도_알림은_하나다() throws Exception {
        long projectId = createProject(jdbc);
        rule(projectId, "regression");
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    AlertEngine engine = new AlertEngine(jdbc, redis, (p, w, t) -> Map.of(), Clock.systemUTC(), "http://console");
                    ready.countDown();
                    ready.await();
                    return engine.fire(projectId, "regression", "regressed:7:1700000000", "재발", "", "/issues/7");
                }));
            }
        }
        int fired = 0;
        for (Future<Integer> f : results) {
            fired += f.get();
        }
        assertThat(fired).isEqualTo(1);
        assertThat(count(projectId)).isEqualTo(1);
    }

    @Test
    void 알림의_링크는_콘솔의_시스템_경로다() {
        long projectId = createProject(jdbc);
        rule(projectId, "new_issue");
        long systemId = jdbc.sql("SELECT system_id FROM projects WHERE id = ?").param(projectId).query(Long.class).single();
        new AlertEngine(jdbc, redis, (p, w, t) -> Map.of(), Clock.systemUTC(), "http://console/")
                .fire(projectId, "new_issue", "new:x", "새 이슈", "", "/issues/9");

        String link = jdbc.sql("SELECT n.link FROM notifications n JOIN alert_rules r ON r.id = n.rule_id WHERE r.project_id = ?")
                .param(projectId).query(String.class).single();
        assertThat(link).isEqualTo("http://console/systems/" + systemId + "/issues/9");
    }

    private void rule(long projectId, String kind) {
        jdbc.sql("INSERT INTO alert_rules (project_id, kind, channel, target, threshold, window_minutes) VALUES (?, ?, 'discord', 'https://192.0.2.1/hook', 1, 1)")
                .params(projectId, kind).update();
    }

    private long count(long projectId) {
        return jdbc.sql("SELECT count(*) FROM notifications n JOIN alert_rules r ON r.id = n.rule_id WHERE r.project_id = ?")
                .param(projectId).query(Long.class).single();
    }
}
