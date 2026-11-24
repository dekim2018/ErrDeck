// 도메인 모델, 지문 계산, 소스맵 해석, 봉투 형식. 스프링 없이 JDK 만으로 동작한다(4.4).
plugins {
    `java-library`
    `maven-publish`
    // 예제 봉투를 sdk-spring 과 worker 의 테스트가 같은 파일로 쓰게 한다.
    `java-test-fixtures`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 감시 대상이 의존성으로 받도록 GitHub Packages 에 올린다(23 작업, CHOICES 23-1). sdk-v* 태그에서 CI 가 올린다.
java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "errdeck-core"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/dekim2018/ErrDeck")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

