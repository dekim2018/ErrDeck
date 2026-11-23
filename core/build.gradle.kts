// 도메인 모델, 지문 계산, 소스맵 해석, 봉투 형식. 스프링 없이 JDK 만으로 동작한다(4.4).
plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
