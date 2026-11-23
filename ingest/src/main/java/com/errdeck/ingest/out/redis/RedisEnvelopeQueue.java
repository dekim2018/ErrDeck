package com.errdeck.ingest.out.redis;

import com.errdeck.ingest.app.EnvelopeQueue;
import com.errdeck.ingest.app.QueueStats;
import com.errdeck.ingest.config.IngestProperties;
import com.errdeck.ingest.domain.Admission;
import com.errdeck.ingest.domain.ProjectKey;
import com.errdeck.ingest.domain.Stream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

@Component
public class RedisEnvelopeQueue implements EnvelopeQueue, QueueStats {

    /** 본문은 바이트 그대로, 나머지 인자는 문자열로 보낸다. */
    private static final RedisSerializer<Object> ARGS = new RedisSerializer<>() {
        @Override
        public byte[] serialize(Object value) {
            return value instanceof byte[] bytes ? bytes : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object deserialize(byte[] bytes) {
            throw new UnsupportedOperationException();
        }
    };

    /** 스크립트가 돌려주는 목록 안의 문자열을 읽는다. 정수는 그대로 Long 으로 온다. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisSerializer<List> RESULT = (RedisSerializer) RedisSerializer.string();

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT = RedisScript.of(new ClassPathResource("redis/admit.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STATS = RedisScript.of(new ClassPathResource("redis/queue-stats.lua"), List.class);

    private final StringRedisTemplate redis;
    private final IngestProperties properties;

    public RedisEnvelopeQueue(StringRedisTemplate redis, IngestProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Admission admit(ProjectKey key, Stream stream, byte[] body) {
        List<?> result = redis.execute(ADMIT, ARGS, RESULT,
                List.of("errdeck:rl:" + key.projectId(), stream.key()),
                key.rateLimitPerMin(),
                properties.rateLimit().burst(),
                properties.rateLimit().bucketTtl().toMillis(),
                maxLength(stream),
                key.projectId(),
                body);
        long status = ((Number) result.get(0)).longValue();
        return switch ((int) status) {
            case 202 -> new Admission.Accepted();
            case 429 -> new Admission.RateLimited(Duration.ofMillis(((Number) result.get(1)).longValue()));
            case 503 -> new Admission.QueueFull();
            default -> throw new IllegalStateException("스크립트가 모르는 값을 돌려주었다: " + result);
        };
    }

    @Override
    public Snapshot snapshot(Stream stream) {
        List<?> result = redis.execute(STATS, ARGS, RESULT, List.of(stream.key()));
        long length = ((Number) result.get(0)).longValue();
        String oldestId = (String) result.get(1);
        long now = ((Number) result.get(2)).longValue();
        if (oldestId.isEmpty()) {
            return new Snapshot(length, Duration.ZERO);
        }
        long addedAt = Long.parseLong(oldestId.substring(0, oldestId.indexOf('-')));
        return new Snapshot(length, Duration.ofMillis(Math.max(0, now - addedAt)));
    }

    private long maxLength(Stream stream) {
        return switch (stream) {
            case EVENTS -> properties.queue().eventsMaxLength();
            case TX -> properties.queue().txMaxLength();
        };
    }
}
