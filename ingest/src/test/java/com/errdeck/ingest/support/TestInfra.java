package com.errdeck.ingest.support;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트가 함께 쓰는 PostgreSQL 과 Redis 컨테이너. 테스트 실행 동안 한 번만 띄운다.
 *
 * <p>스키마는 api 가 소유하므로 api 의 Flyway 스크립트를 그대로 적용한다. 경로는 build.gradle.kts 가 넘긴다.
 */
public abstract class TestInfra {

    public static final PostgreSQLContainer POSTGRES = startPostgres();
    public static final GenericContainer<?> REDIS = startRedis();

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17");
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("filesystem:" + System.getProperty("errdeck.migrations"))
                .load()
                .migrate();
        return container;
    }

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7").withExposedPorts(6379);
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
