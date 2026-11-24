package com.errdeck.api.out.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.app.query.ScopedQuery;
import com.errdeck.api.support.TestInfra;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** api 사용자로 실행한 쿼리에 다른 프로젝트의 행이 섞이지 않는지 실제 ClickHouse 로 본다. */
@SpringBootTest
class ClickHouseReaderTest extends TestInfra {

    @Autowired
    ClickHouseReader reader;

    @Test
    void 볼_수_있는_프로젝트의_행만_돌려준다() {
        ClickHouseHttp admin = new ClickHouseHttp(URI.create(clickHouseUrl()), "errdeck", "errdeck", Duration.ofSeconds(10));
        admin.execute("errdeck", """
                INSERT INTO event_ticks (project_id, fingerprint, timestamp, count, user_hash) VALUES
                (701, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', now64(3), 3, 1),
                (702, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', now64(3), 5, 2)
                """);
        Instant to = Instant.now().plusSeconds(60);

        List<Map<String, Object>> rows = reader.query(ScopedQuery.from("issue_stats_1m", Set.of(701L), "minute", to.minus(Duration.ofHours(1)), to)
                .select("project_id", "fingerprint", "sum(times) AS times")
                .groupBy("project_id", "fingerprint"));
        List<Map<String, Object>> none = reader.query(ScopedQuery.from("issue_stats_1m", Set.of(), "minute", to.minus(Duration.ofHours(1)), to)
                .select("count() AS c"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("project_id", 701L).containsEntry("times", 3L);
        assertThat(none.getFirst()).containsEntry("c", 0L);
    }
}
