// 감시 대상이 의존성으로 추가하는 Spring Boot Starter SDK. 구현은 09 작업에서 한다.
plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
