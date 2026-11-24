// 감시 대상이 의존성으로 추가하는 Spring Boot Starter SDK(11.2).
// 스프링, 서블릿, WebFlux, Logback 은 감시 대상이 이미 가진 것을 쓰도록 compileOnly 로 둔다. 있는 것에만 붙는다.
plugins {
    `java-library`
}

val bootBom = "org.springframework.boot:spring-boot-dependencies:4.1.1"

dependencies {
    // 봉투 형식과 이벤트 모델을 ingest, worker 와 같은 코드로 쓴다.
    api(project(":core"))

    compileOnly(platform(bootBom))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-webmvc")
    compileOnly("org.springframework.boot:spring-boot-webflux")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")

    testImplementation(platform(bootBom))
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
