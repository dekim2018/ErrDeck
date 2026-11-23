package com.errdeck.api.out.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClickHouseMigratorTest {

    @Test
    void 줄_끝의_세미콜론으로_문장을_나누고_주석만_있는_조각은_버린다() {
        List<String> statements = ClickHouseMigrator.statements("""
                -- 첫 문장
                CREATE TABLE a (x UInt8) ENGINE = Null;

                CREATE TABLE b (s String DEFAULT 'a;b') ENGINE = Null;
                -- 끝의 주석
                """);

        assertThat(statements).containsExactly(
                "-- 첫 문장\nCREATE TABLE a (x UInt8) ENGINE = Null",
                "CREATE TABLE b (s String DEFAULT 'a;b') ENGINE = Null");
    }

    @Test
    void 파일은_번호_순서대로_읽고_번호는_겹치지_않는다() {
        List<ClickHouseMigrator.Migration> migrations = ClickHouseMigrator.load();

        assertThat(migrations).extracting(ClickHouseMigrator.Migration::version).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void 자리_표시에_따옴표나_공백이_섞인_값은_거부한다() {
        ClickHouseHttp http = new ClickHouseHttp(URI.create("http://localhost:1"), "u", "p", Duration.ofSeconds(1));

        assertThatThrownBy(() -> new ClickHouseMigrator(http, "errdeck", Map.of("api_password", "x' OR '1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClickHouseMigrator(http, "errdeck", Map.of("api_password", "two words")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
