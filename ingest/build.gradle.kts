// 수집 프로세스. 봉투를 검사해 대기열에 넣기만 한다(adr/0003). Spring Security 는 넣지 않는다(15.3).
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // 가상 스레드가 실행 스레드를 붙잡은 횟수(jvm.threads.virtual.pinned, 14.4). JFR 로 잰다.
    runtimeOnly("io.micrometer:micrometer-java21")

    testImplementation(testFixtures(project(":core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // 스키마는 api 가 소유한다. 테스트에서만 api 의 Flyway 스크립트를 컨테이너에 적용한다.
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    val migrations = project(":api").file("src/main/resources/db/migration")
    inputs.dir(migrations)
    systemProperty("errdeck.migrations", migrations.absolutePath)
}
