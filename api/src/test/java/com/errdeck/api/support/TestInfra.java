package com.errdeck.api.support;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트가 함께 쓰는 PostgreSQL, ClickHouse, Redis 컨테이너. 테스트 실행 동안 한 번만 띄운다.
 * 스키마는 api 가 뜰 때 Flyway 와 ClickHouse 실행기가 만든다.
 */
public abstract class TestInfra {

    public static final PostgreSQLContainer POSTGRES = startPostgres();
    public static final GenericContainer<?> CLICKHOUSE = startClickHouse();
    public static final GenericContainer<?> REDIS = startRedis();
    /** 소스맵 볼륨 대신 쓰는 임시 폴더. */
    public static final java.nio.file.Path FILES = tempDir();

    private static java.nio.file.Path tempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("errdeck-artifacts");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7").withExposedPorts(6379);
        container.start();
        return container;
    }

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17");
        container.start();
        return container;
    }

    private static GenericContainer<?> startClickHouse() {
        GenericContainer<?> container = new GenericContainer<>("clickhouse:26.3")
                .withEnv("CLICKHOUSE_DB", "errdeck")
                .withEnv("CLICKHOUSE_USER", "errdeck")
                .withEnv("CLICKHOUSE_PASSWORD", "errdeck")
                .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                .withExposedPorts(8123)
                .waitingFor(Wait.forHttp("/ping").forPort(8123).withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        return container;
    }

    public static String clickHouseUrl() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("errdeck.clickhouse.url", TestInfra::clickHouseUrl);
        registry.add("errdeck.files.dir", () -> FILES.toString());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
