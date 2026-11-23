package com.errdeck.ingest.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.ingest.domain.Platform;
import com.errdeck.ingest.domain.ProjectKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectKeyCacheTest {

    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    private final AtomicInteger lookups = new AtomicInteger();

    @Test
    void 없는_키도_기억해서_같은_키로_다시_와도_DB를_치지_않는다() {
        ProjectKeyCache cache = cache(Map.of(), NOW);

        assertThat(cache.findActive("unknown")).isEmpty();
        assertThat(cache.findActive("unknown")).isEmpty();

        assertThat(lookups).hasValue(1);
    }

    @Test
    void 형식이_틀린_키는_DB에_묻지_않는다() {
        ProjectKeyCache cache = cache(Map.of(), NOW);

        assertThat(cache.findActive(null)).isEmpty();
        assertThat(cache.findActive("")).isEmpty();
        assertThat(cache.findActive("a".repeat(65))).isEmpty();
        assertThat(cache.findActive("공백 섞인 키")).isEmpty();

        assertThat(lookups).hasValue(0);
    }

    @Test
    void 캐시에_있는_동안_폐기_시각이_지나면_그때부터_거부한다() {
        ProjectKey key = new ProjectKey(1, Platform.SPRING, 6000, Set.of(), NOW.plusSeconds(10));
        MutableClock clock = new MutableClock(NOW);
        ProjectKeyCache cache = new ProjectKeyCache(repository(Map.of("k", key)), IngestServiceTest.properties(256), clock);

        assertThat(cache.findActive("k")).contains(key);
        clock.now = NOW.plusSeconds(11);
        assertThat(cache.findActive("k")).isEmpty();
        assertThat(lookups).hasValue(1);
    }

    private ProjectKeyCache cache(Map<String, ProjectKey> keys, Instant now) {
        return new ProjectKeyCache(repository(keys), IngestServiceTest.properties(256), Clock.fixed(now, ZoneOffset.UTC));
    }

    private ProjectKeyRepository repository(Map<String, ProjectKey> keys) {
        return publicKey -> {
            lookups.incrementAndGet();
            return Optional.ofNullable(keys.get(publicKey));
        };
    }

    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
