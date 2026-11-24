package com.errdeck.worker.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 테스트가 함께 쓰는 Redis 와 ClickHouse 컨테이너. 테스트 실행 동안 한 번만 띄운다.
 *
 * <p>ClickHouse 스키마는 api 가 소유하므로 api 의 SQL 파일을 순서대로 적용한다. worker 는 실제와 같이
 * {@code errdeck_worker} 사용자로 쓴다.
 */
public abstract class TestInfra {

    public static final GenericContainer<?> REDIS = startRedis();
    public static final GenericContainer<?> CLICKHOUSE = startClickHouse();

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7").withExposedPorts(6379);
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
        applySchema("http://" + container.getHost() + ":" + container.getMappedPort(8123));
        return container;
    }

    public static String clickHouseUrl() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }

    /** 관리 사용자로 SQL 하나를 실행하고 결과를 돌려준다. */
    public static String clickHouse(String sql) {
        return execute(clickHouseUrl(), sql);
    }

    private static void applySchema(String url) {
        Path dir = Path.of(System.getProperty("errdeck.clickhouse-schema"));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) {
                String sql = Files.readString(file)
                        .replace("${database}", "errdeck")
                        .replace("${worker_user}", "errdeck_worker")
                        .replace("${worker_password}", "errdeck_worker")
                        .replace("${api_user}", "errdeck_api")
                        .replace("${api_password}", "errdeck_api");
                for (String statement : sql.split(";\\s*(\\n|$)")) {
                    if (statement.lines().anyMatch(l -> !l.isBlank() && !l.strip().startsWith("--"))) {
                        execute(url, statement);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String execute(String url, String sql) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url + "/?database=errdeck"))
                            .header("X-ClickHouse-User", "errdeck")
                            .header("X-ClickHouse-Key", "errdeck")
                            .POST(HttpRequest.BodyPublishers.ofString(sql))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("errdeck.clickhouse.url", TestInfra::clickHouseUrl);
    }
}
