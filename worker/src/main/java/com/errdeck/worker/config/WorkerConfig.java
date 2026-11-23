package com.errdeck.worker.config;

import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.fingerprint.AppFrames;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.worker.app.EnvelopeProcessor;
import com.errdeck.worker.app.Pipeline;
import com.errdeck.worker.app.WorkerMetrics;
import com.errdeck.worker.in.scheduler.PipelineLifecycle;
import com.errdeck.worker.out.clickhouse.ClickHouseEventSink;
import com.errdeck.worker.out.redis.RedisQueueSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
@EnableConfigurationProperties({WorkerProperties.class, ClickHouseProperties.class})
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
    ClickHouseEventSink eventSink(ClickHouseProperties properties) {
        return new ClickHouseEventSink(properties);
    }

    @Bean
    EnvelopeProcessor envelopeProcessor(WorkerProperties properties, WorkerMetrics metrics) {
        return new EnvelopeProcessor(new EnvelopeCodec(EnvelopeLimits.DEFAULT),
                new Fingerprinter(new AppFrames(properties.inAppPackages())), metrics);
    }

    @Bean
    Pipeline pipeline(RedisQueueSource source, ClickHouseEventSink sink, EnvelopeProcessor processor, WorkerMetrics metrics,
                      WorkerProperties properties, Clock clock) {
        return new Pipeline(source, sink, processor, metrics, properties, clock);
    }

    @Bean
    PipelineLifecycle pipelineLifecycle(Pipeline pipeline, RedisQueueSource source) {
        return new PipelineLifecycle(pipeline, source);
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
