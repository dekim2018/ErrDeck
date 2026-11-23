package com.errdeck.api.config;

import com.errdeck.api.out.clickhouse.ClickHouseHttp;
import com.errdeck.api.out.clickhouse.ClickHouseMigrator;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClickHouseProperties.class)
public class ClickHouseConfig {

    @Bean
    ClickHouseMigrator clickHouseMigrator(ClickHouseProperties properties) {
        ClickHouseHttp admin = new ClickHouseHttp(properties.url(), properties.user(), properties.password(), properties.timeout());
        return new ClickHouseMigrator(admin, properties.database(), Map.of(
                "worker_user", properties.workerUser(),
                "worker_password", properties.workerPassword(),
                "api_user", properties.apiUser(),
                "api_password", properties.apiPassword()));
    }

    /** PostgreSQL 은 Flyway 가, ClickHouse 는 이 실행기가 api 가 뜰 때 스키마를 맞춘다. 실패하면 api 는 뜨지 않는다. */
    @Bean
    @ConditionalOnBooleanProperty(name = "errdeck.clickhouse.migrate", matchIfMissing = true)
    ApplicationRunner clickHouseSchema(ClickHouseMigrator migrator) {
        return args -> migrator.migrate();
    }
}
