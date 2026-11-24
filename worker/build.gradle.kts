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
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(testFixtures(project(":core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ClickHouse 스키마는 api 가 소유한다. 테스트에서만 api 의 SQL 파일을 컨테이너에 적용한다.
tasks.test {
    val schema = project(":api").file("src/main/resources/clickhouse")
    inputs.dir(schema)
    systemProperty("errdeck.clickhouse-schema", schema.absolutePath)
}
