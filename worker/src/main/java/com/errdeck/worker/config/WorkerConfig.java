package com.errdeck.worker.config;

import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.fingerprint.AppFrames;
import com.errdeck.core.files.LocalArtifactStore;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.core.sourcemap.SourceMapCache;
import com.errdeck.core.sourcemap.StackRestorer;
import com.errdeck.worker.app.EnvelopeProcessor;
import com.errdeck.worker.app.Pipeline;
import com.errdeck.worker.app.WorkerMetrics;
import com.errdeck.worker.in.scheduler.PipelineLifecycle;
import com.errdeck.worker.app.IssueBatchWriter;
import com.errdeck.worker.out.clickhouse.ClickHouseEventStore;
import com.errdeck.worker.out.persistence.JdbcIssueRepository;
import com.errdeck.worker.out.redis.RedisSeenEntries;
import com.errdeck.worker.out.redis.RedisQueueSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties({WorkerProperties.class, ClickHouseProperties.class, com.errdeck.worker.uptime.UptimeProperties.class})
public class WorkerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RedisQueueSource queueSource(RedisConnectionFactory factory, WorkerProperties properties) {
        return new RedisQueueSource(factory, properties.consumerGroup(), consumerName());
    }

    @Bean
    ClickHouseEventStore clickHouseEventStore(ClickHouseProperties clickHouse) {
        return new ClickHouseEventStore(clickHouse);
    }

    @Bean
    IssueBatchWriter batchWriter(StringRedisTemplate redis, JdbcClient jdbc, JdbcTemplate template, TransactionTemplate transactions,
                                 ClickHouseEventStore store, WorkerProperties properties, WorkerMetrics metrics, Clock clock) {
        return new IssueBatchWriter(
                new RedisSeenEntries(redis, properties.seenTtl()),
                new JdbcIssueRepository(jdbc, template, transactions),
                store,
                metrics, clock, properties.issueCacheSize(), properties.samplesPerIssuePerMinute());
    }

    @Bean
    StackRestorer stackRestorer(WorkerProperties properties, io.micrometer.core.instrument.MeterRegistry registry) {
        SourceMapCache<StackRestorer.ArtifactKey> cache = new SourceMapCache<>(properties.sourcemapCacheSize().toBytes());
        StackRestorer restorer = new StackRestorer(new LocalArtifactStore(java.nio.file.Path.of(properties.filesDir())), cache);
        // errdeck_sourcemap_cache_total{result}: 캐시 적중, 해석, 소스맵 없음(13.4)
        io.micrometer.core.instrument.FunctionCounter.builder("errdeck.sourcemap.cache", cache, SourceMapCache::hits)
                .tag("result", "hit").register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("errdeck.sourcemap.cache", cache, SourceMapCache::loads)
                .tag("result", "load").register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("errdeck.sourcemap.cache", restorer, StackRestorer::missing)
                .tag("result", "missing").register(registry);
        return restorer;
    }

    @Bean
    EnvelopeProcessor envelopeProcessor(WorkerProperties properties, StackRestorer restorer, WorkerMetrics metrics) {
        return new EnvelopeProcessor(new EnvelopeCodec(EnvelopeLimits.DEFAULT),
                new Fingerprinter(new AppFrames(properties.inAppPackages())), restorer, metrics);
    }

    @Bean
    Pipeline pipeline(RedisQueueSource source, IssueBatchWriter writer, EnvelopeProcessor processor, WorkerMetrics metrics,
                      WorkerProperties properties, Clock clock) {
        return new Pipeline(source, writer, processor, metrics, properties, clock);
    }

    @Bean
    PipelineLifecycle pipelineLifecycle(Pipeline pipeline, RedisQueueSource source) {
        return new PipelineLifecycle(pipeline, source);
    }

    /** 사용자가 넣은 주소를 부르기 전의 확인(9.4). 업타임 점검과 알림 웹훅이 같이 쓴다. */
    @Bean
    com.errdeck.core.net.UrlGuard urlGuard(com.errdeck.worker.uptime.UptimeProperties uptime) {
        return new com.errdeck.core.net.UrlGuard(java.util.Set.copyOf(uptime.allowedHosts()));
    }

    @Bean
    com.errdeck.worker.alert.AlertEngine alertEngine(JdbcClient jdbc, StringRedisTemplate redis, ClickHouseEventStore store, Clock clock,
                                                     @org.springframework.beans.factory.annotation.Value("${errdeck.console-url}") String consoleUrl) {
        return new com.errdeck.worker.alert.AlertEngine(jdbc, redis, store, clock, consoleUrl);
    }

    @Bean
    com.errdeck.worker.alert.AlertPoller alertPoller(com.errdeck.worker.alert.AlertEngine engine) {
        return new com.errdeck.worker.alert.AlertPoller(engine);
    }

    @Bean
    com.errdeck.worker.alert.NotificationDispatcher notificationDispatcher(JdbcClient jdbc, StringRedisTemplate redis,
                                                                           com.errdeck.core.net.UrlGuard guard,
                                                                           io.micrometer.core.instrument.MeterRegistry registry, Clock clock) {
        return new com.errdeck.worker.alert.NotificationDispatcher(jdbc, redis, guard, registry, clock, consumerName());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty(name = "errdeck.uptime.enabled", matchIfMissing = true)
    com.errdeck.worker.uptime.UptimeRunner uptimeRunner(JdbcClient jdbc, TransactionTemplate transactions, com.errdeck.core.net.UrlGuard guard,
                                                        ClickHouseEventStore store, com.errdeck.worker.alert.AlertEngine alerts,
                                                        StringRedisTemplate redis, com.errdeck.worker.uptime.UptimeProperties properties,
                                                        io.micrometer.core.instrument.MeterRegistry registry, Clock clock) {
        return new com.errdeck.worker.uptime.UptimeRunner(new com.errdeck.worker.uptime.MonitorStore(jdbc, transactions),
                new com.errdeck.worker.uptime.UptimeChecker(guard, clock), store, alerts, redis, properties, registry, clock);
    }

    /**
     * 소비 그룹 안에서 worker 를 구별하는 이름. 다시 뜰 때마다 달라지므로, 죽은 worker 가 남긴 미확인 항목은
     * 이어받기로 가져온다(6.7).
     */
    private static String consumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "worker";
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}
