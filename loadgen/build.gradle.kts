// 부하와 폭주를 만드는 명령줄 도구. 봉투 형식을 core 와 같게 쓰려고 core 에 의존한다. 시나리오는 04 작업에서 만든다.
plugins {
    java
    application
}

dependencies {
    implementation(project(":core"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.errdeck.loadgen.LoadGen")
}
