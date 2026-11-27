// 조회와 설정 프로세스. 콘솔 로그인과 권한(15장)을 맡고, PostgreSQL 과 ClickHouse 스키마도 이 프로세스가 적용한다.
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // 가상 스레드가 실행 스레드를 붙잡은 횟수(jvm.threads.virtual.pinned, 14.4). JFR 로 잰다.
    runtimeOnly("io.micrometer:micrometer-java21")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Lettuce 의 DNS 캐시 TTL 을 줄이려고 직접 쓴다(RedisClientConfig). 버전은 Spring Boot 가 관리한다.
    implementation("io.netty:netty-resolver-dns")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
