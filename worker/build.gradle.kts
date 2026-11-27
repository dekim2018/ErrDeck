// 처리 프로세스. 계산은 고정 풀에서 하므로 가상 스레드를 켜지 않는다(adr/0009). 웹 서버는 상태 확인과 지표에만 쓴다.
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Lettuce 의 DNS 캐시 TTL 을 줄이려고 직접 쓴다(RedisClientConfig). 버전은 Spring Boot 가 관리한다.
    implementation("io.netty:netty-resolver-dns")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(testFixtures(project(":core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // 스키마는 api 가 소유한다. 테스트에서만 api 의 Flyway 스크립트를 적용한다.
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 스키마는 api 가 소유한다. 테스트에서만 api 의 SQL 파일과 Flyway 스크립트를 컨테이너에 적용한다.
tasks.test {
    val schema = project(":api").file("src/main/resources/clickhouse")
    val migrations = project(":api").file("src/main/resources/db/migration")
    inputs.dir(schema)
    inputs.dir(migrations)
    systemProperty("errdeck.clickhouse-schema", schema.absolutePath)
    systemProperty("errdeck.migrations", migrations.absolutePath)
}
