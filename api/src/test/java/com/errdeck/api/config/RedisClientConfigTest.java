package com.errdeck.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisClientConfigTest {

    @Test
    void DNS_캐시는_응답의_TTL_이_길어도_설정한_최대값까지만_기억하고_실패는_1초만_기억한다() {
        var cache = RedisClientConfig.dnsCache(Duration.ofSeconds(5));

        assertThat(cache.minTtl()).isZero();
        assertThat(cache.maxTtl()).isEqualTo(5);
        assertThat(cache.negativeTtl()).isEqualTo(1);
    }
}
