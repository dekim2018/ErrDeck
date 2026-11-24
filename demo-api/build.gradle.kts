// 데모 앱. Starter SDK 를 붙여 예외 경로마다 이슈가 생기는지 보고, SDK 의 부담을 잰다(10 작업).
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":sdk-spring"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(testFixtures(project(":core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
