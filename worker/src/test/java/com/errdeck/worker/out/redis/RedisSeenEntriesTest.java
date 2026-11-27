package com.errdeck.worker.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.worker.domain.EntryRef;
import com.errdeck.worker.domain.QueueStream;
import com.errdeck.worker.support.TestInfra;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 처리한 항목 기록을 실제 Redis 로 확인한다. 스프링 없이 어댑터만 쓴다. */
class RedisSeenEntriesTest extends TestInfra {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void 남긴_항목만_처리한_것으로_답하고_보관_시간이_걸린다() {
        RedisSeenEntries seen = new RedisSeenEntries(redis, Duration.ofMinutes(10));
        EntryRef done = new EntryRef(QueueStream.EVENTS, UUID.randomUUID() + "-0");
        EntryRef fresh = new EntryRef(QueueStream.EVENTS, UUID.randomUUID() + "-0");

        seen.markSeen(List.of(done));

        assertThat(seen.alreadySeen(List.of(done, fresh))).containsExactly(done);
        assertThat(redis.getExpire("errdeck:seen:events:" + done.id())).isBetween(590L, 600L);
    }

    @Test
    void 대기열이_다르면_같은_항목_ID_라도_다른_항목이다() {
        RedisSeenEntries seen = new RedisSeenEntries(redis, Duration.ofMinutes(10));
        String id = UUID.randomUUID() + "-0";

        seen.markSeen(List.of(new EntryRef(QueueStream.EVENTS, id)));

        assertThat(seen.alreadySeen(List.of(new EntryRef(QueueStream.TX, id)))).isEmpty();
    }
}
