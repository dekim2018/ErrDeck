# 감시 대상 연결: 팬파라이브와 CoPage

팬파라이브(스프링 서비스 5개와 Vue)와 CoPage(스프링 앱과 Vue)에 ErrDeck 을 붙이는 방법이다(23 작업). 두 저장소를 직접 고치는 일은 이 저장소 밖이므로 여기에는 방법만 적는다. 실제로 고칠 때는 아래 순서를 그대로 따른다.

## 0. 로컬에서만 연결하기(CoPage, 27 작업에서 확인)

서버에 올리지 않아도 같은 맥에서 CoPage 와 ErrDeck 을 함께 띄워 연결할 수 있다. 에러, 추적, 요약 카드, 업타임, 알림이 모두 된다.

| 무엇 | 어떻게 |
| --- | --- |
| SDK | `./gradlew :core:publishToMavenLocal :sdk-spring:publishToMavenLocal`. CoPage 는 `-Perrdeck=true` 로 빌드할 때만 `mavenLocal()` 에서 넣는다. 화면은 빌드한 파일을 CoPage 에 함께 둔다(`npm run errdeck:sync`) |
| 프로젝트와 DSN | `scripts/seed/systems.sql` 다음에 `scripts/seed/copage-local.sql`. 화면의 출처(5173, 5174)를 허용하고 앱의 상태 모니터를 만들고 DSN 두 개를 출력한다. ingest 는 `localhost:18080` |
| 지표 | ErrDeck 의 프로메테우스가 `host.docker.internal:8081`(CoPage 관리 포트)을 `copage-app` 으로 긁는다. CoPage 를 `COPAGE_MANAGEMENT_ADDRESS=0.0.0.0` 으로 띄워야 도커 안에서 닿는다 |
| 가리기 | 서버 `errdeck.redact-paths`(정규식, 첫 괄호까지 남김), 화면 `redact` 함수. CoPage 는 Sentry 와 같은 규칙을 준다 |

자세한 순서는 CoPage 저장소의 README "ErrDeck 으로 모니터링(로컬)" 에 있다.

## 1. 시스템과 프로젝트 등록

```bash
docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql
```

시스템 4개(팬파라이브, CoPage, ErrDeck, 데모 앱)와 프로젝트 13개(3.2)가 생기고 프로젝트마다 DSN 키가 하나씩 생긴다. 마지막 출력의 `public_key` 와 `project_id` 로 DSN 을 만든다.

```
https://{public_key}@{ErrDeck 도메인}/{project_id}
```

콘솔에서 보려면 가입한 사용자를 시스템의 OWNER 로 넣는다(파일 끝의 주석).

## 2. 스프링 서비스(팬파라이브 5개, CoPage app)

### 의존성

SDK 는 GitHub Packages 에 있다(`sdk-v*` 태그에서 CI 가 올린다). 읽기 권한이 있는 토큰(`read:packages`)이 필요하다.

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/dekim2018/ErrDeck")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()
        }
    }
}

dependencies {
    implementation("com.errdeck:errdeck-sdk-spring:0.1.0")
    // 요약 카드와 성능 화면의 엔드포인트 표(12.2, 8.1)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}
```

로컬에서 먼저 써 볼 때는 이 저장소에서 `./gradlew :core:publishToMavenLocal :sdk-spring:publishToMavenLocal` 하고 `mavenLocal()` 을 저장소로 둔다.

### 설정

```yaml
errdeck:
  dsn: ${ERRDECK_DSN:}            # 없거나 비어 있으면 SDK 가 꺼진다
  redact-paths:                   # 경로와 예외 메시지에서 가릴 것. 첫 괄호까지 남기고 가린다
    - (/s/)[^/?#]+
  environment: production
  release: ${GIT_COMMIT:unknown}  # 재발 판정과 릴리스 화면(6.6)이 이 값을 쓴다
  in-app-packages: com.livebroadcast
  tracing:
    sample-rate: 0.1              # 시작할 때 뽑는 비율(8.4)
    slow-threshold: 1s

management:
  server:
    port: 8091                    # 지표는 서비스 포트와 나눈다(13.2). 프록시에 연결하지 않는다
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- gateway(WebFlux)는 같은 의존성으로 웹 필터가 붙는다. 들어오는 요청을 트랜잭션으로 남기고 뒤로 넘기는 `traceparent` 를 자기 것으로 바꾼다.
- 서비스 사이 호출은 `RestClient.Builder` 나 `RestTemplate` 빈을 써야 `traceparent` 가 붙는다(`RestClient.create()` 로 직접 만든 것은 붙지 않는다).
- SSE(댓글, 알림)와 WebSocket(CoPage)은 연결 수립까지만 기록한다(8.3).
- 팬파라이브의 interaction-service 처럼 결제사를 부르는 구간은 사용자 정의 구간으로 남길 수 있다.

```java
try (var span = Tracing.span("payment", "결제사 승인")) {
    gateway.approve(order);
}
```

## 3. Vue 화면(팬파라이브 web, CoPage web)

```bash
npm install @dekim2018/errdeck-sdk-vue --registry=https://npm.pkg.github.com
```

```ts
import { createErrDeck } from '@dekim2018/errdeck-sdk-vue'

app.use(createErrDeck({
  dsn: import.meta.env.VITE_ERRDECK_DSN,
  release: __RELEASE__,
  environment: import.meta.env.MODE,
  router,
  tracesSampleRate: 0.1,
}))
```

- ErrDeck 콘솔에서 프로젝트의 허용 출처에 화면의 주소를 넣는다(5.2). 시드 스크립트가 duckdns 주소를 넣어 둔다.
- 빌드 뒤 소스맵을 올린다. 릴리스는 `createErrDeck` 에 넣은 값과 같아야 한다.

```bash
ERRDECK_TOKEN=edt_... scripts/upload-sourcemaps.sh https://{ErrDeck 도메인} {project_id} {release} dist
```

## 4. 지표: 감시 대상 서버의 프로메테우스 에이전트

팬파라이브 서버(AWS)는 다른 서버라 에이전트가 밀어 보낸다(13.3). 팬파라이브 서버에서:

```bash
# targets.yml 은 deploy/agent/targets.example.yml 을 본떠, job 이름을 ErrDeck 프로젝트 슬러그와 같게 쓴다
ERRDECK_REMOTE_WRITE_URL=https://{ErrDeck 도메인}/prometheus/api/v1/write \
ERRDECK_AGENT_PASSWORD=... \
ERRDECK_AGENT_TARGETS_FILE=./targets.yml \
ERRDECK_AGENT_NETWORK=팬파라이브의_도커_네트워크 \
docker compose -f docker-compose.agent.yml up -d
```

비밀번호의 해시는 ErrDeck 서버의 `.env` 에 `ERRDECK_AGENT_PASSWORD_HASH` 로 넣는다(DEPLOY-OCI.md). 같은 서버(로컬 개발)에 있으면 에이전트 없이 `deploy/prometheus/prometheus.yml` 에 수집 대상을 더한다.

## 5. 업타임과 알림

- 콘솔의 프로젝트 > 모니터에서 `https://fanpalive.duckdns.org/actuator/health` 같은 공개 상태 주소를 넣는다. 팬파라이브는 평소 꺼 두므로 꺼 둘 때는 모니터를 중지한다(9.1).
- 프로젝트 > 알림에서 Discord 웹훅으로 새 이슈, 재발, 다운 규칙을 만든다.

## 확인

두 서비스를 띄우고 에러를 낸 뒤 통합 화면(전체 보기)에서 두 시스템의 에러와 요약 카드가 함께 보이면 끝이다(23 작업의 끝 기준). 이 저장소의 데모 앱 둘(`/demo/chain`)로 같은 흐름을 미리 확인했다.
