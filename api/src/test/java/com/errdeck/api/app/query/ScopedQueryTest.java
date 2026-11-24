package com.errdeck.api.app.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.errdeck.api.app.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopedQueryTest {

    private static final Instant TO = Instant.parse("2026-11-24T00:00:00Z");

    @Test
    void 프로젝트_조건과_기간_조건을_항상_넣는다() {
        ScopedQuery query = ScopedQuery.from("issue_stats_1m", Set.of(3L, 1L), "minute", TO.minus(Duration.ofDays(1)), TO)
                .select("fingerprint", "sum(times) AS times")
                .where("fingerprint IN {fps:Array(String)}", "fps", List.of("a", "b'c"))
                .groupBy("fingerprint")
                .orderBy("times DESC")
                .limit(25);

        assertThat(query.sql()).isEqualTo("SELECT fingerprint, sum(times) AS times FROM issue_stats_1m"
                + " WHERE project_id IN {scope_projects:Array(UInt32)} AND minute >= {scope_from:DateTime64(3)}"
                + " AND minute < {scope_to:DateTime64(3)} AND (fingerprint IN {fps:Array(String)})"
                + " GROUP BY fingerprint ORDER BY times DESC LIMIT 25");
        assertThat(query.parameters()).containsEntry("scope_projects", "[1,3]")
                .containsEntry("scope_from", "2026-11-23 00:00:00.000")
                .containsEntry("scope_to", "2026-11-24 00:00:00.000")
                .containsEntry("fps", "['a','b\\'c']");
    }

    @Test
    void 볼_수_있는_프로젝트가_없으면_아무_행도_나오지_않는다() {
        String sql = ScopedQuery.from("events", Set.of(), "timestamp", TO.minusSeconds(60), TO).select("count()").sql();

        assertThat(sql).contains("WHERE 0 AND");
    }

    @Test
    void 기간이_30일을_넘거나_거꾸로면_거부한다() {
        assertThatThrownBy(() -> ScopedQuery.from("events", Set.of(1L), "timestamp", TO.minus(Duration.ofDays(31)), TO))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ScopedQuery.from("events", Set.of(1L), "timestamp", TO, TO.minusSeconds(1)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 범위_매개변수의_이름을_덮어쓸_수_없다() {
        ScopedQuery query = ScopedQuery.from("events", Set.of(1L), "timestamp", TO.minusSeconds(60), TO);

        assertThatThrownBy(() -> query.where("1", "scope_projects", "[2]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.where("1", "x; DROP", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScopedQuery.from("events; DROP TABLE x", Set.of(1L), "timestamp", TO.minusSeconds(60), TO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
