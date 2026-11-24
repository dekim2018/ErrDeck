package com.errdeck.api.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.app.EventStats.Bucket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueQueryServiceTest {

    @Test
    void 발생이_없는_구간도_0으로_채워_기간_전체의_막대를_만든다() {
        Instant to = Instant.parse("2026-11-24T12:30:00Z");
        Instant from = to.minus(Duration.ofHours(24));

        List<Bucket> filled = IssueQueryService.fill(List.of(new Bucket(Instant.parse("2026-11-24T05:00:00Z"), 7)), from, to,
                Duration.ofHours(1));

        assertThat(filled).hasSize(25);
        assertThat(filled.getFirst().start()).isEqualTo(Instant.parse("2026-11-23T12:00:00Z"));
        assertThat(filled.stream().mapToLong(Bucket::times).sum()).isEqualTo(7);
        assertThat(filled).filteredOn(b -> b.times() == 7).extracting(Bucket::start).containsExactly(Instant.parse("2026-11-24T05:00:00Z"));
    }
}
