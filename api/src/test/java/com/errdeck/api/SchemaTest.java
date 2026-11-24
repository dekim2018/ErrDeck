package com.errdeck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.errdeck.api.out.clickhouse.ClickHouseException;
import com.errdeck.api.out.clickhouse.ClickHouseHttp;
import com.errdeck.api.out.clickhouse.ClickHouseMigrator;
import com.errdeck.api.support.TestInfra;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 빈 저장소에서 스키마가 만들어지고, 두 번 실행해도 같은 상태인지 본다(06 작업). */
@SpringBootTest
class SchemaTest extends TestInfra {

    private static final List<String> CLICKHOUSE_TABLES =
            List.of("event_ticks", "events", "issue_stats_1m", "issue_stats_1m_mv", "schema_migrations", "transactions", "uptime_checks");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    ClickHouseMigrator migrator;

    private final ClickHouseHttp admin = http("errdeck", "errdeck");

    @Test
    void PostgreSQL에_테이블_16개가_만들어진다() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();

        assertThat(tables).containsExactly(
                "alert_rules", "api_tokens", "issue_comments", "issue_fingerprints", "issues", "monitor_incidents", "monitors",
                "notifications", "project_keys", "projects", "quarantined_events", "release_artifacts", "releases", "system_members",
                "systems", "users");
    }

    @Test
    void Flyway를_다시_실행하면_아무것도_하지_않는다() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void ClickHouse에_테이블_5개와_구체화_뷰와_실행_기록이_만들어진다() {
        assertThat(clickHouseTables()).containsExactlyElementsOf(CLICKHOUSE_TABLES);
        assertThat(migrator.appliedVersions()).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void ClickHouse_실행기를_다시_돌리면_아무것도_실행하지_않고_같은_상태다() {
        List<Integer> ran = migrator.migrate();

        assertThat(ran).isEmpty();
        assertThat(clickHouseTables()).containsExactlyElementsOf(CLICKHOUSE_TABLES);
        assertThat(admin.execute("errdeck", "SELECT count() FROM schema_migrations").strip()).isEqualTo("9");
    }

    @Test
    void 건수_테이블에_넣으면_구체화_뷰가_분_단위로_합치고_사용자_없는_이벤트는_사용자로_세지_않는다() {
        String fingerprint = "f".repeat(31) + "1";
        admin.execute("errdeck", """
                INSERT INTO event_ticks (project_id, fingerprint, timestamp, count, user_hash) VALUES
                (999, '%1$s', '2026-11-24 10:00:01.000', 1, 11),
                (999, '%1$s', '2026-11-24 10:00:30.000', 2, 11),
                (999, '%1$s', '2026-11-24 10:00:59.999', 3, 22),
                (999, '%1$s', '2026-11-24 10:01:00.000', 4, 33),
                (999, '%1$s', '2026-11-24 10:01:30.000', 9, 0)
                """.formatted(fingerprint));

        String rows = admin.execute("errdeck", """
                SELECT minute, sum(times), uniqMerge(users) FROM issue_stats_1m
                WHERE project_id = 999 AND fingerprint = '%s'
                GROUP BY minute ORDER BY minute FORMAT TabSeparated
                """.formatted(fingerprint));

        assertThat(rows.strip().split("\n")).containsExactly(
                "2026-11-24 10:00:00\t6\t2",
                "2026-11-24 10:01:00\t13\t1");
        // Null 엔진이므로 건수 테이블 자체에는 아무것도 남지 않는다.
        assertThat(admin.execute("errdeck", "SELECT count() FROM event_ticks").strip()).isEqualTo("0");
    }

    @Test
    void api_사용자는_읽기만_하고_조회_상한이_걸려_있다() {
        ClickHouseHttp api = http("errdeck_api", "errdeck_api");

        assertThat(api.execute("errdeck", "SELECT count() FROM events").strip()).isEqualTo("0");
        assertThat(api.execute("errdeck", "SELECT getSetting('max_execution_time'), getSetting('max_threads') FORMAT TabSeparated").strip())
                .isEqualTo("10\t2");
        assertThatThrownBy(() -> api.execute("errdeck", "INSERT INTO event_ticks (project_id) VALUES (1)"))
                .isInstanceOf(ClickHouseException.class);
        assertThatThrownBy(() -> api.execute("errdeck", "DROP TABLE events"))
                .isInstanceOf(ClickHouseException.class);
    }

    @Test
    void worker_사용자는_쓰고_읽을_수_있지만_테이블을_지울_수는_없다() {
        ClickHouseHttp worker = http("errdeck_worker", "errdeck_worker");

        worker.execute("errdeck", "INSERT INTO uptime_checks (monitor_id, project_id, timestamp, ok) VALUES (1, 1, now64(3), 1)");

        assertThat(worker.execute("errdeck", "SELECT count() FROM uptime_checks WHERE monitor_id = 1").strip()).isEqualTo("1");
        assertThatThrownBy(() -> worker.execute("errdeck", "DROP TABLE uptime_checks"))
                .isInstanceOf(ClickHouseException.class);
    }

    private List<String> clickHouseTables() {
        return List.of(admin.execute("errdeck",
                "SELECT name FROM system.tables WHERE database = 'errdeck' ORDER BY name FORMAT TabSeparated").strip().split("\n"));
    }

    private static ClickHouseHttp http(String user, String password) {
        return new ClickHouseHttp(URI.create(clickHouseUrl()), user, password, Duration.ofSeconds(10));
    }
}
