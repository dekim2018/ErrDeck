# 작업 순서

이 문서는 저장소에서 실행하는 Claude가 읽고 따르는 작업 지침이다. 작업 하나는 이슈 하나, 브랜치 하나, PR 하나다. 사용자가 "docs/WORKFLOW.md의 03 작업을 진행해줘"처럼 번호로 지시하면, 1장의 절차대로 4장의 해당 작업을 수행한다.

번호 없이 "다음 작업을 진행해줘"라고 하면 5장의 진행 체크에서 체크되지 않은 가장 앞 번호를 진행한다.

## 1. 작업 하나의 절차

아래 아홉 단계를 순서대로 한다. 어느 단계에서든 2.1의 "멈추고 묻는 경우"에 해당하면 진행을 멈추고 사용자에게 묻는다.

### 1.1 읽기

1. 이 문서의 1장, 2장, 3장.
2. 4장에서 해당 작업의 "읽을 문서"에 적힌 장. 장 번호와 파일의 대응은 [README.md](./README.md)에 있다.
3. 읽을 문서가 가리키는 [adr/](./adr/README.md)의 결정 기록.
4. 앞 작업이 만든 코드 중 이번 작업이 고치거나 부르는 부분.

### 1.2 시작 전 확인

```bash
git status --short            # 아무것도 안 나와야 한다
git switch develop
git pull --ff-only
```

- 작업 폴더가 깨끗하지 않으면 무엇이 남아 있는지 사용자에게 알리고 멈춘다. 01 작업만 예외다(4장의 01 참고).
- 앞 번호 작업이 5장에서 체크되어 있는지 본다. 안 되어 있으면 사용자에게 알린다.
- 해당 작업에 "먼저 정할 것"이 있고 아직 정해지지 않았으면 사용자에게 묻는다.

### 1.3 이슈 만들기

제목은 4장의 "이슈 제목"을 쓴다. 본문은 아래 형식으로 쓴다.

```bash
gh issue create --title "<이슈 제목>" --body "$(cat <<'BODY'
## 무엇을

<한두 문장>

## 범위

- <만드는 것>

## 범위 밖

- <이번에 하지 않는 것과 그것을 하는 작업 번호>

## 끝났다고 보는 기준

- [ ] <4장의 기준>
BODY
)"
```

출력된 주소의 끝 숫자가 이슈 번호다. PR 본문에 쓴다.

### 1.4 브랜치 만들기

```bash
git switch -c feature/<번호>-<이름>
```

이름은 4장의 "브랜치"를 그대로 쓴다.

### 1.5 구현

- 4장의 "만드는 것"만 만든다. 뒤 작업의 범위를 미리 만들지 않는다.
- 설계 문서의 값(큐 길이, 제한 시간, 상한 등)을 그대로 쓴다. 값은 상수로 흩어 놓지 않고 설정 속성으로 모은다.
- 테스트를 함께 쓴다. 테스트 이름은 한글로 무엇을 확인하는지 적는다.
- 커밋은 의미 단위로 나눈다. 한 작업에 보통 3개에서 6개다.

### 1.6 확인

```bash
./gradlew build
```

- 4장의 "확인"에 적힌 명령을 추가로 실행한다.
- 저장소가 필요한 확인은 `docker compose up -d` 로 먼저 띄운다.
- 실패하면 원인을 고친다. 테스트를 지우거나 건너뛰게 해서 통과시키지 않는다.
- 실행하지 못한 확인은 실행하지 못했다고 PR 본문에 적는다.

### 1.7 문서 갱신

- 구현하면서 설계와 달라진 점이 있으면 해당 장을 고친다. 무엇이 왜 달라졌는지 그 장의 끝에 "구현하면서 바뀐 점" 절로 남긴다.
- 결정을 바꿨으면 [adr/](./adr/README.md)의 규칙대로 기존 문서의 상태를 바꾸고 새 번호로 추가한다.
- 이 문서 5장의 진행 체크에서 해당 작업을 체크한다.
- 루트 `README.md` 의 "지금 상태"를 고친다.
- 측정한 값은 `docs/EXPERIMENTS.md` 에 환경(코어 수, 메모리, 커밋)과 함께 적는다.

### 1.8 푸시와 PR

```bash
git push -u origin feature/<번호>-<이름>
gh pr create --base develop --title "<이슈 제목>" --body "$(cat <<'BODY'
Closes #<이슈 번호>

## 바뀐 것

- <커밋 단위로 한 줄씩>

## 확인한 것

- <실행한 명령과 결과>

## 확인하지 못한 것

- <없으면 "없음">

## 설계와 달라진 점

- <없으면 "없음">
BODY
)"
```

### 1.9 보고

PR 주소, 확인한 것, 확인하지 못한 것, 사용자가 정해야 할 것을 짧게 보고하고 멈춘다. PR을 합치는 것은 사용자가 한다. 사용자가 합친 뒤 다음 작업을 지시한다.

## 2. 규칙

### 2.1 멈추고 묻는 경우

- 작업 범위나 설계 문서의 내용이 애매하거나 서로 어긋날 때. 추측해서 진행하지 않는다.
- 설계 문서의 값이나 구조를 바꿔야만 구현할 수 있을 때. 바꾸려는 내용과 이유를 먼저 알린다.
- 화면의 색, 배치, 차트 모양을 정해야 할 때. 시안을 먼저 만들어 보여 주고 사용자가 고른 뒤 구현한다.
- 4장에 "먼저 정할 것"으로 적힌 항목.
- 빌드나 테스트가 같은 원인으로 세 번 실패할 때.
- 이 저장소 밖(팬파라이브, CoPage 저장소)을 고쳐야 할 때.

### 2.2 git

- 브랜치: `feature/NN-이름`. 번호는 작업마다 1씩 늘어난다. develop에서 만든다.
- 커밋 메시지: 제목은 `feat(ingest): ...`, `fix(worker): ...`, `docs: ...`, `chore(build): ...`, `test(core): ...` 형식. 괄호 안은 모듈 이름이다. 본문은 한국어로 무엇을 왜 했는지 쓴다.
- 커밋 작성자는 저장소에 설정된 사용자다. 공동 작성자 표시나 도구 서명을 넣지 않는다(`.claude/settings.json`).
- develop과 main에 직접 푸시하지 않는다. 강제 푸시하지 않는다.
- 비밀 값(`.env`, 키, 토큰)을 커밋하지 않는다. 로컬 개발용 기본값만 설정 파일에 둔다.

### 2.3 문서와 주석

- 평서체(한다체)로 쓴다.
- 가운뎃점을 쓰지 않는다. 나열은 쉼표로 한다.
- 본문에 개수를 적었으면 표나 목록의 실제 개수와 맞는지 센다.
- 성능 숫자는 실행해서 잰 것만 적는다. 설계 문서의 숫자는 목표값이거나 처음 설정값이다. 재지 않은 결과를 지어내지 않는다.
- 주석은 코드가 무엇을 하는지가 아니라 왜 그렇게 하는지를 적는다.

### 2.4 코드

- 이름 규칙은 [README.md](./README.md)의 표를 따른다.
- `core` 는 스프링에 의존하지 않는다. JDK만 쓴다.
- ingest, worker, api는 서로 직접 호출하지 않는다. 대기열과 저장소로만 이어진다([adr/0001](./adr/0001-three-processes-by-load.md)).
- 외부 호출에는 반드시 제한 시간을 둔다(14.9). 큐에는 반드시 길이를 둔다([adr/0010](./adr/0010-bounded-queues-backpressure.md)).
- 가상 스레드는 기다리는 일에만 쓴다. 계산 작업은 고정 풀에서 한다([adr/0009](./adr/0009-virtual-threads-only-for-waiting.md)).
- 저장소 접근은 포트(인터페이스) 뒤에 둔다. 패키지는 `domain`, `app`(포트와 서비스), `in`(web, scheduler), `out`(persistence, redis, clickhouse), `config` 로 나눈다.
- PostgreSQL 접근은 JdbcClient로 한다. JPA를 쓰지 않는다.

## 3. 기술 기준

아래 이름과 버전은 같은 사용자의 CoPage, 팬파라이브 저장소에서 빌드가 통과한 것이다. 다른 이름을 추측해서 쓰지 않는다.

| 항목 | 값 |
| --- | --- |
| 자바 | 25 (Gradle toolchain) |
| Gradle | 9.1.0 래퍼, Kotlin DSL(`build.gradle.kts`) |
| Spring Boot 플러그인 | `org.springframework.boot` 4.1.1 |
| 의존성 관리 플러그인 | `io.spring.dependency-management` 1.1.7 |
| 웹 | `org.springframework.boot:spring-boot-starter-webmvc` |
| 상태 확인 | `org.springframework.boot:spring-boot-starter-actuator` |
| DB | `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`, `org.postgresql:postgresql` |
| Redis | `spring-boot-starter-data-redis` |
| 보안 | `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` |
| 검증 | `spring-boot-starter-validation` |
| 테스트 | `spring-boot-starter-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-postgresql`, `org.junit.platform:junit-platform-launcher` |
| 순수 Java 모듈의 테스트 | `org.junit:junit-bom:5.13.4` 와 `org.junit.jupiter:junit-jupiter` |
| JSON | Jackson 3 (`tools.jackson`). Spring Boot 4.1.1이 관리하는 버전을 쓴다 |
| 로컬 캐시 | `com.github.ben-manes.caffeine:caffeine`. Spring Boot가 버전을 관리한다 |
| 지표 | `io.micrometer:micrometer-registry-prometheus`. Spring Boot가 버전을 관리한다 |
| 테스트의 스키마 적용 | `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`. ingest 테스트가 api의 Flyway 스크립트를 적용할 때 쓴다 |

위 표에 없는 의존성(ClickHouse 클라이언트, Caffeine, 프로메테우스 등록기 등)은 처음 쓰는 작업에서 Spring Boot가 버전을 관리하는지 확인하고, 관리하지 않으면 버전을 명시한다. 추가한 의존성은 이 표에 한 줄 더한다.

| 로컬 저장소 | 이미지 | 기본 포트 | 계정 |
| --- | --- | --- | --- |
| PostgreSQL | `postgres:17` | 15432 | errdeck / errdeck, DB `errdeck` |
| Redis | `redis:7` | 16379 | 없음 |
| ClickHouse | `clickhouse:26.3` | 18123 (HTTP) | errdeck / errdeck, DB `errdeck` |

포트를 15432, 16379, 18123으로 잡은 것은 팬파라이브, CoPage와 함께 띄워도 겹치지 않게 하기 위해서다.

| 프로세스 | 서비스 포트 | 환경 변수 |
| --- | --- | --- |
| ingest | 8080 | `ERRDECK_INGEST_PORT` |
| api | 8081 | `ERRDECK_API_PORT` |
| worker | 8082 (상태 확인과 지표만) | `ERRDECK_WORKER_PORT` |

## 4. 작업 목록

작업은 26개이고 [17-ROADMAP.md](./17-ROADMAP.md)의 8단계를 따른다.

### 1단계. 골격과 수집

#### 01 골격과 문서

- 브랜치: `feature/01-skeleton`
- 이슈 제목: 골격과 문서
- 읽을 문서: 4.4, 17.3, [adr/0001](./adr/0001-three-processes-by-load.md), [adr/0013](./adr/0013-compose-first-kubernetes-optional.md)
- 시작 상태: `docs/` 폴더가 커밋되지 않은 채로 있다. 이 작업에서만 작업 폴더가 깨끗하지 않아도 된다. 브랜치를 만든 뒤 `docs/` 를 첫 커밋(`docs: 기획과 설계 문서, 결정 기록, 작업 순서`)으로 넣는다.
- 만드는 것
    - `.gitignore`, `.gitattributes`, `.editorconfig`
    - `.claude/settings.json`: 커밋과 PR에 도구 서명을 넣지 않는 설정. CoPage 저장소의 것과 같은 내용
    - 루트 `CLAUDE.md`: 작업 전에 `docs/WORKFLOW.md` 를 읽으라는 짧은 안내. 만들기 전에 사용자에게 필요한지 묻는다
    - Gradle 래퍼, `settings.gradle.kts`, 루트 `build.gradle.kts`(그룹 `com.errdeck`, toolchain 25, UTF-8, `-Xlint:all`, JUnit Platform)
    - 모듈 6개: `core`(java-library), `ingest`, `worker`, `api`(Spring Boot), `sdk-spring`(java-library), `loadgen`(java, core에 의존)
    - ingest, worker, api: `@SpringBootApplication` 하나, `application.yml`(애플리케이션 이름, 3장의 포트, health 노출), 애플리케이션이 뜨는지 보는 테스트 하나. 가상 스레드는 ingest와 api만 켠다
    - `docker-compose.yml`: 3장의 저장소 세 개. 각각 healthcheck와 볼륨. Redis는 `--appendonly yes --appendfsync everysec --maxmemory 256mb --maxmemory-policy noeviction`
    - `.github/workflows/ci.yml`: PR과 develop, main 푸시에서 `./gradlew build`
    - 루트 `README.md`: 소개, 지금 상태, 모듈 표, 빌드와 실행 방법, 문서 링크, 작업 방식
- 끝났다고 보는 기준: `./gradlew build` 통과. 저장소 3개가 healthy. ingest의 상태 확인이 UP
- 확인

```bash
./gradlew build
docker compose up -d && docker compose ps
docker compose exec postgres psql -U errdeck -c 'select version()'
docker compose exec redis redis-cli config get maxmemory-policy
curl -s 'http://localhost:18123/?user=errdeck&password=errdeck' --data 'SELECT version()'
./gradlew :ingest:bootRun &
sleep 20 && curl -s http://localhost:8080/actuator/health; kill %1
```

#### 02 봉투 형식과 DSN

- 브랜치: `feature/02-envelope`
- 이슈 제목: core: 봉투 형식과 DSN
- 읽을 문서: 5.2, 5.3, 5.4, 11.1
- 만드는 것
    - `Dsn`: `https://{공개키}@{호스트}/{프로젝트ID}` 해석과 검증
    - 봉투 모델: 머리 줄, 항목 3종(event, transaction, client_report)
    - 봉투 쓰기와 읽기. 줄 단위 JSON, 항목의 `length` 로 본문을 자른다
    - 압축과 해제. 해제는 출력 2MB 상한을 넘으면 전부 읽지 않고 중단한다
    - 이벤트 모델: 예외 사슬, 스택 프레임, 요청 정보, 태그, 빵부스러기, 릴리스, 환경, `count`
    - 예제 봉투 파일(테스트 자원). SDK와 worker가 같은 파일로 형식을 맞춘다
- 끝났다고 보는 기준: 예제 봉투의 쓰기와 읽기 왕복이 같다. 상한을 넘는 본문, 잘린 본문, 형식이 틀린 줄을 예외로 거부한다
- 확인: `./gradlew :core:test`

#### 03 수집 엔드포인트와 한도

- 브랜치: `feature/03-ingest`
- 이슈 제목: ingest: 수집 엔드포인트와 한도
- 읽을 문서: 5장 전체, 14.3, 14.4, 14.9, 15.3, 15.5, [adr/0002](./adr/0002-bounded-redis-streams.md), [adr/0003](./adr/0003-ingest-does-not-parse.md)
- 만드는 것
    - `POST /ingest/{projectId}/envelope`. 키는 `X-ErrDeck-Key` 헤더 또는 `?key=`
    - 인증 필터 하나. Spring Security는 넣지 않는다
    - 키 조회와 로컬 캐시(30초, 없는 키도 기억). 이 작업에서는 `projects`, `project_keys` 두 테이블만 Flyway로 만든다
    - 출처 검사
    - Lua 스크립트 하나: 토큰 버킷 차감, 대기열 길이 확인, XADD. 스트림은 `errdeck:stream:events`, `errdeck:stream:tx`
    - 응답 202, 413, 429, 503, 401과 403. 429와 503에는 `Retry-After`
    - 진행 중인 Redis 호출을 세마포어 256으로 제한. 자리가 없으면 바로 503
    - Redis 호출 제한 시간 200ms
    - 지표: `errdeck_ingest_requests_total`, `errdeck_ingest_envelope_bytes`, `errdeck_queue_length`, `errdeck_queue_oldest_age_seconds`. 프로메테우스 엔드포인트 노출
- 끝났다고 보는 기준: curl로 보낸 봉투가 스트림에 들어간다. 한도를 넘으면 429, 대기열이 차면 503. 동시에 보낸 요청 중 한도를 넘겨 들어간 건이 없다
- 확인: `./gradlew :ingest:test`. 통합 테스트는 실제 Redis와 PostgreSQL 컨테이너로 한다

#### 04 부하 도구와 폭주 실험 1차

- 브랜치: `feature/04-loadgen`
- 이슈 제목: loadgen: 부하 도구와 폭주 실험 1차
- 읽을 문서: 2.2, 5.1, 18.2, 18.5
- 만드는 것
    - 명령줄 도구. 시나리오 2개: 지속 부하(초당 건수, 시간, 지문 종류 수), 폭주(같은 에러, 초당 건수, 시간)
    - 실제 봉투 형식으로 보낸다. 응답 코드별 건수와 응답 시간 분포(p50, p99)를 출력한다
    - `docs/EXPERIMENTS.md`: 실험별 표의 틀. 이 작업에서 실행한 것만 값을 채운다
- 끝났다고 보는 기준: 폭주 부하에서 ingest가 계속 응답하고 대기열이 상한에서 멈춘다. 결과를 문서에 기록한다
- 확인: 로컬에서 두 시나리오를 실행하고 출력을 PR 본문에 붙인다

### 2단계. 처리와 저장

#### 05 정규화와 지문 규칙

- 브랜치: `feature/05-fingerprint`
- 이슈 제목: core: 정규화와 지문 규칙
- 읽을 문서: 6.2, 6.3, 6.4, 6.8
- 만드는 것
    - 앱 프레임 판정(패키지 목록, `node_modules`)
    - 6.3의 규칙 9개
    - 값을 지운 메시지(숫자, UUID, 16진수, 따옴표 안의 문자열)
    - 지문: 예외 사슬의 타입과 앱 프레임의 클래스, 메서드 이름을 SHA-256으로. 규칙 버전을 함께 돌려준다
    - SDK가 직접 지정한 지문을 우선한다
- 끝났다고 보는 기준: 규칙마다 사례 테스트가 있다. 줄 번호, 프록시 번호, 람다 번호만 다른 스택 두 개는 같은 지문, 다른 버그는 다른 지문
- 확인: `./gradlew :core:test`

#### 06 저장소 스키마

- 브랜치: `feature/06-storage`
- 이슈 제목: 저장소 스키마
- 읽을 문서: 10장 전체, [adr/0005](./adr/0005-events-in-clickhouse-issues-in-postgres.md), [adr/0006](./adr/0006-link-by-fingerprint-not-issue-id.md)
- 만드는 것
    - PostgreSQL Flyway 스크립트. 10.1의 테이블 16개 중 03 작업에서 만든 2개를 뺀 나머지
    - ClickHouse 테이블 5개와 구체화 뷰. 번호 붙인 SQL 파일과, 적용한 번호를 기록하며 순서대로 실행하는 작은 실행기
    - ClickHouse 사용자 2개(worker용, api용)와 조회 상한 설정(10.5)
- 끝났다고 보는 기준: 빈 저장소에서 스키마가 만들어진다. 두 번 실행해도 같은 상태다
- 확인: 통합 테스트에서 테이블 목록을 조회해 개수를 확인한다

#### 07 worker 단계 구조와 역압

- 브랜치: `feature/07-worker-pipeline`
- 이슈 제목: worker: 단계 구조와 역압
- 읽을 문서: 6.1, 6.7, 10.4, 14.3, 14.5, 14.6, [adr/0010](./adr/0010-bounded-queues-backpressure.md)
- 만드는 것
    - 읽기 스레드 2개(스트림당 하나, 전용 Redis 연결), 넘겨주기 큐 2,000, 계산 풀(크기는 설정, 기본 2), 쓰기 큐 10,000, 쓰기 스레드 1개
    - 에러 큐 우선, 에러 3건마다 트랜잭션 1건
    - 묶음 쓰기: 1초 또는 5,000행. 쓰기에 성공한 뒤에만 XACK
    - 미확인 항목 이어받기(XAUTOCLAIM)
    - 확인 처리한 항목은 XDEL로 지운다. ingest가 XLEN으로 대기열 길이 상한을 확인하기 때문이다(5장 끝)
    - 종료 절차: 읽기를 멈추고 처리 중인 묶음을 쓰고 확인 처리한 뒤 끝낸다. 제한 30초
    - 지표: `errdeck_worker_stage_seconds`, `errdeck_clickhouse_batch_rows`, `errdeck_clickhouse_flush_seconds`, `errdeck_e2e_latency_seconds`
    - 이 작업에서는 이벤트를 지문만 계산해 `events` 에 넣는다. 이슈 연결은 08 작업
- 끝났다고 보는 기준: 보낸 건수와 저장 건수가 같다. ClickHouse를 멈추면 큐가 차례로 차고 ingest가 503을 응답하며, worker의 힙이 계속 늘지 않는다
- 확인: loadgen의 지속 부하 뒤 `SELECT count() FROM events`. ClickHouse 정지는 `docker compose stop clickhouse` 로 한다

#### 08 이슈, 집계, 중복, 격리

- 브랜치: `feature/08-issues`
- 이슈 제목: worker: 이슈, 집계, 중복, 격리
- 읽을 문서: 6.4, 6.5, 6.6, 6.7, 10.3, [adr/0004](./adr/0004-exact-counts-bounded-samples.md)
- 만드는 것
    - 쓰기 단계에서 묶음 안의 처음 보는 지문을 모아 이슈를 찾거나 만든다. `ON CONFLICT DO NOTHING`
    - 지문과 이슈의 대응 캐시
    - 받은 이벤트 전부를 `event_ticks` 에, 이슈별 분당 상한(기본 100) 안의 것만 `events` 에
    - 이슈의 최초 발생, 최근 발생을 묶음당 이슈별로 한 번만 갱신
    - event_id 중복 확인(`errdeck:seen:{eventId}`, 1시간)
    - 세 번 실패한 항목을 `quarantined_events` 로 옮긴다
    - 지표: `errdeck_events_processed_total`
- 끝났다고 보는 기준: 같은 에러 1만 건이 이슈 1개로 남고, 집계 건수는 1만, 원본은 상한 이하다. 같은 새 지문을 동시에 보내도 이슈는 하나다
- 확인: loadgen의 폭주 뒤 `issue_stats_1m` 의 합계와 `events` 의 건수를 조회한다

### 3단계. 스프링 SDK와 콘솔 뼈대

#### 09 Starter SDK

- 브랜치: `feature/09-sdk-spring`
- 이슈 제목: sdk-spring: Starter SDK
- 읽을 문서: 11.1, 11.2, 11.5, 15.6
- 먼저 정할 것: 팬파라이브의 gateway를 프로젝트로 등록할지. gateway는 WebFlux 기반이다. 등록하면 WebFilter 구현을 이 작업에 넣는다
- 만드는 것
    - 자동 설정과 설정 속성(`errdeck.dsn`, `environment`, `release`, `in-app-packages`, `enabled`)
    - 예외를 잡는 5경로: 리졸버와 필터(최종 상태 코드 500 이상만), 스케줄러와 비동기 핸들러, 로그 어펜더, 미처리 예외 핸들러, `ErrDeck.capture(e)`
    - 전송 큐(1,000), 전송 스레드 1개(2초 또는 50건), 제한 시간 3초, 재시도 1회
    - 같은 에러 접기(10초 안에 5건 초과), 429와 503의 `Retry-After` 따르기, client_report
    - 민감한 헤더와 필드 지우기
    - 종료 시 최대 2초 비우기
    - SDK 내부 오류는 감시 대상으로 던지지 않는다
- 끝났다고 보는 기준: 가짜 ingest를 상대로 큐 넘침, 거절 응답, 종료 시 비우기 테스트가 통과한다. ingest가 없어도 요청 스레드가 막히지 않는다
- 확인: `./gradlew :sdk-spring:test`

#### 10 데모 앱

- 브랜치: `feature/10-demo-app`
- 이슈 제목: 데모 앱
- 읽을 문서: 3.2, 11.5, 18.2
- 만드는 것
    - `demo-api` 모듈: 처리된 예외, 처리되지 않은 예외, 비동기 예외, 스케줄 예외, 400번대 예외를 내는 엔드포인트
    - compose의 `demo` 프로필
    - SDK를 켠 것과 끈 것의 요청 시간을 비교하는 loadgen 시나리오
- 끝났다고 보는 기준: 데모 앱의 500번대 예외가 ClickHouse에 저장되고 400번대 예외는 저장되지 않는다. SDK 부담 측정값을 `EXPERIMENTS.md` 에 기록한다
- 확인: 엔드포인트를 호출한 뒤 `events` 와 PostgreSQL의 `issues` 를 조회한다

#### 11 로그인과 권한

- 브랜치: `feature/11-auth`
- 이슈 제목: api: 로그인과 권한
- 읽을 문서: 15.1, 15.2, 15.4, 15.5
- 만드는 것
    - 필터 체인 3개(내부, 프로젝트 토큰, JWT)
    - 가입, 로그인, 재발급, 로그아웃. 액세스 토큰 15분, 리프레시 토큰 회전과 재사용 감지
    - 시스템, 프로젝트, 구성원, DSN 키 관리 API. 역할 4종
    - ClickHouse 쿼리 생성기에 프로젝트 ID 조건 강제
- 끝났다고 보는 기준: 15.4 권한 표의 모든 칸이 통합 테스트로 검증된다. 다른 시스템의 데이터를 조회할 수 없다
- 확인: `./gradlew :api:test`

#### 12 콘솔 뼈대와 이슈 화면

- 브랜치: `feature/12-console-issues`
- 이슈 제목: web: 콘솔 뼈대와 이슈 화면
- 읽을 문서: 12.3, 6.5, 6.6
- 먼저 정할 것: 화면 시안. 시안을 2개 이상 만들어 보여 주고 사용자가 고른 뒤 구현한다
- 만드는 것
    - `web` 폴더: Vue 3, Vite, Pinia, Vue Router
    - 로그인, 시스템과 프로젝트 선택, 이슈 목록(거르기와 정렬), 이슈 상세(스택, 요청 정보, 발생 추이, 상태 변경, 댓글)
    - 이슈 조회 API(PostgreSQL의 25건과 ClickHouse의 횟수를 합침)
    - CI에 프론트 빌드와 테스트 추가
- 끝났다고 보는 기준: 데모 앱의 예외가 화면에 이슈로 보이고 상태를 바꿀 수 있다
- 확인: `npm run build`, `npm test`, 화면에서 직접 확인

### 4단계. Vue SDK와 소스맵

#### 13 소스맵 해석과 캐시

- 브랜치: `feature/13-sourcemap`
- 이슈 제목: core: 소스맵 해석과 캐시
- 읽을 문서: 7.3, 7.4, 7.6, [adr/0007](./adr/0007-sourcemap-parser-in-java.md)
- 만드는 것
    - Base64 VLQ 해석, `mappings` 를 `int[]` 다섯 개로 담는 표현, 줄 안의 이진 탐색
    - `sourcesContent` 에서 앞뒤 5줄 추출
    - 바이트 합계로 제한하는 캐시(기본 128MB). 같은 키의 동시 해석은 한 번만
    - 테스트 자원: 실제 Vite 빌드 결과물과, 기존 라이브러리로 미리 구한 정답 위치 목록
- 끝났다고 보는 기준: 정답 목록의 모든 위치가 일치한다. 같은 소스맵을 여러 스레드가 동시에 요청해도 해석 횟수가 1이다
- 확인: `./gradlew :core:test`

#### 14 Vue SDK

- 브랜치: `feature/14-sdk-vue`
- 이슈 제목: sdk-vue: Vue SDK
- 읽을 문서: 11.1, 11.3, 5.2
- 만드는 것
    - `sdk-vue` 폴더(npm 패키지, 외부 의존성 없음)
    - 에러를 잡는 3경로, 빵부스러기(최근 50개), 같은 에러 접기
    - 전송: `text/plain`, `?key=`, `enc=gzip`, `keepalive`, 탭이 숨겨질 때 `sendBeacon`
    - `demo-web`: 에러를 내는 작은 Vue 화면
- 끝났다고 보는 기준: 브라우저가 사전 요청 없이 보낸다. 압축된 번들의 에러가 ingest에 도착한다. 번들 크기를 기록한다
- 확인: `npm test`, 브라우저 개발자 도구의 네트워크 탭에서 OPTIONS 요청이 없는지 확인

#### 15 릴리스와 소스맵 업로드, 재발 판정

- 브랜치: `feature/15-releases`
- 이슈 제목: 릴리스와 소스맵 업로드, 재발 판정
- 읽을 문서: 7.2, 7.5, 6.6, 15.2
- 만드는 것
    - 프로젝트 토큰 발급과 폐기
    - 릴리스 등록과 소스맵 업로드 API, 파일 저장 포트와 로컬 볼륨 어댑터
    - 빌드 뒤 소스맵을 올리는 스크립트
    - worker의 복원 단계. 못 찾으면 복원 안 됨으로 표시하고 대체 지문을 쓴다
    - 이슈 상태 전이(6.6의 표 6줄). 릴리스 화면
- 끝났다고 보는 기준: 압축된 번들의 에러가 원본 파일과 줄로 보인다. 해결한 릴리스보다 이전 릴리스의 에러는 재발로 치지 않는다
- 확인: demo-web을 두 번 빌드해 두 릴리스로 올리고 에러를 낸다

### 5단계. 관측과 자원 실험

#### 16 지표, 대시보드, 경보

- 브랜치: `feature/16-observability`
- 이슈 제목: 관측: 지표, 대시보드, 경보
- 읽을 문서: 13장 전체, 14.1, 14.7, 14.8, [adr/0012](./adr/0012-remote-write-from-agents.md)
- 만드는 것
    - compose의 `obs` 프로필: Prometheus, Grafana, Alertmanager, exporter 4종
    - 13.4의 지표 12개 중 아직 없는 것
    - 대시보드 5개와 데이터 소스를 파일로
    - 경보 규칙 8개. Alertmanager에서 api의 웹훅으로
    - 컨테이너별 CPU 상한(14.1)과 메모리 상한(14.7), JVM 옵션(코어 수 명시, 힙, GC, 메모리 부족 시 종료)
    - ingest, worker, api의 Dockerfile과 compose 등록
- 끝났다고 보는 기준: `docker compose --profile core --profile obs up -d` 한 번으로 대시보드가 뜬다. worker를 내리면 경보가 api에 도착한다
- 확인: 그라파나 화면, `docker stats` 로 상한 확인

#### 17 자원 실험

- 브랜치: `feature/17-resource-experiments`
- 이슈 제목: 자원 실험
- 읽을 문서: 14장 전체, 18.3, 18.5
- 만드는 것
    - 18.3의 실험 7종을 실행하는 스크립트(`scripts/experiments`)
    - 결과를 `EXPERIMENTS.md` 에 기록. 설계 값이 틀렸으면 14장과 설정을 고친다
- 끝났다고 보는 기준: 7종의 결과 표가 실측값으로 채워진다. 실행하지 못한 실험은 이유와 함께 비워 둔다
- 확인: 스크립트 출력과 대시보드 화면을 PR에 붙인다

### 6단계. 성능 추적

#### 18 추적 SDK

- 브랜치: `feature/18-tracing-sdk`
- 이슈 제목: 추적: 전파, 계측, 표본 추출
- 읽을 문서: 8.2, 8.3, 8.4, 8.5, [adr/0008](./adr/0008-metrics-for-stats-traces-for-causes.md)
- 만드는 것
    - `traceparent` 읽기와 쓰기(Spring SDK, Vue SDK)
    - 계측: 들어오는 요청, 나가는 HTTP 호출, SQL, 사용자 정의 구간, 페이지 이동과 API 호출
    - 시작할 때의 표본 추출과 끝에서 한 번 더 하는 판단(에러, 느린 요청)
    - 트랜잭션당 스팬 500개 제한, 반복 스팬 접기
    - 긴 연결(SSE, WebSocket)은 연결 수립까지만 기록
    - 에러 이벤트에 trace_id 넣기
- 끝났다고 보는 기준: 서비스 2개를 지나는 요청이 같은 trace_id로 ingest에 도착한다. 추적을 켠 것과 끈 것의 요청 시간 차이를 기록한다
- 확인: demo-api를 두 개 띄워 서로 호출하게 한다

#### 19 추적 저장과 성능 화면

- 브랜치: `feature/19-tracing-console`
- 이슈 제목: 추적: 저장과 성능 화면
- 읽을 문서: 8.1, 10.2, 12.3
- 만드는 것
    - worker의 트랜잭션 처리와 `transactions` 저장
    - 엔드포인트 표 API(프로메테우스 질의), 느린 요청 목록, 추적 조회 API
    - 성능 화면과 스팬 그림. 이슈 상세에서 추적으로 가는 링크
- 끝났다고 보는 기준: 서비스 2개를 지나는 요청이 한 그림으로 보인다. 반복 쿼리가 표시된다
- 확인: 화면에서 직접 확인

### 7단계. 업타임, 알림, 통합 화면

#### 20 업타임 점검

- 브랜치: `feature/20-uptime`
- 이슈 제목: 업타임 점검
- 읽을 문서: 9장 전체
- 만드는 것
    - 모니터 관리 API와 화면, 일시 중지
    - 실행 일정(`errdeck:monitors:due`, Lua 스크립트), 가상 스레드와 세마포어 50
    - 판정(9.3의 표 4줄), 장애 기록, 가동률
    - 내부 주소 차단과 허용 목록 설정
    - 인증서 만료일 확인
    - 지표: `errdeck_uptime_schedule_lag_seconds`
- 끝났다고 보는 기준: worker 2대에서 같은 모니터의 중복 점검이 0건이다. 내부 주소를 넣은 모니터는 거부된다
- 확인: `docker compose up -d --scale worker=2`

#### 21 알림

- 브랜치: `feature/21-alerts`
- 이슈 제목: 알림
- 읽을 문서: 16장 전체, 13.6
- 먼저 정할 것: Slack과 Discord 중 먼저 할 채널
- 만드는 것
    - 규칙 7종과 판정
    - 억제 5종. `notifications` 의 유일 제약으로 중복 발송 방지
    - 발송: `errdeck:stream:notify`, 가상 스레드, 동시 10건, 제한 5초, 재시도 3회
    - Alertmanager 웹훅 수신
    - 규칙과 채널 설정 화면
- 끝났다고 보는 기준: 폭주 중 같은 이슈의 알림이 재알림 간격에 한 건만 간다. worker 2대에서 중복 알림이 0건이다
- 확인: 가짜 웹훅 서버로 받은 건수를 센다

#### 22 통합 화면

- 브랜치: `feature/22-overview`
- 이슈 제목: 통합 화면
- 읽을 문서: 12장 전체, [adr/0011](./adr/0011-summary-cards-and-grafana-links.md)
- 먼저 정할 것: 화면 시안
- 만드는 것
    - 전체 보기(시스템 카드, 상태 신호 3단계), 시스템 보기(프로젝트별 한 줄)
    - 요약 카드 API: 프로메테우스 즉시 값 질의 6개, 15초 캐시, 제한 2초
    - 그라파나 링크 4곳(12.4의 표)
- 끝났다고 보는 기준: 시스템별 상태 신호와 요약 카드 지표 6개가 보인다. 프로메테우스를 내려도 화면이 뜬다
- 확인: 화면에서 직접 확인

#### 23 팬파라이브와 CoPage 연결

- 브랜치: `feature/23-integrations`
- 이슈 제목: 팬파라이브와 CoPage 연결
- 읽을 문서: 3.2, 11.4, 12.5, 13.3
- 먼저 정할 것: SDK 패키지를 올릴 곳. 두 저장소를 고치는 작업은 이 저장소 밖이므로 범위와 방법을 사용자에게 먼저 확인한다
- 만드는 것
    - `docker-compose.agent.yml`: 감시 대상 서버에 두는 프로메테우스 에이전트
    - 두 서비스에 붙이는 방법을 적은 문서(`docs/INTEGRATION.md`): 의존성, 설정, Actuator 노출, 소스맵 업로드, 태그
    - 시스템 4개와 프로젝트 13개를 등록하는 초기 데이터 스크립트
- 끝났다고 보는 기준: 두 서비스의 에러와 요약 카드가 한 화면에 보인다
- 확인: 두 서비스를 로컬에서 띄우고 에러를 낸다

### 8단계. 마무리

#### 24 장애 실험

- 브랜치: `feature/24-chaos`
- 이슈 제목: 장애 실험
- 읽을 문서: 18.4, 18.5
- 만드는 것: 18.4의 실험 6종 스크립트(`scripts/chaos`)와 결과 기록
- 끝났다고 보는 기준: 6종의 결과 표가 채워진다. 기대와 다른 결과는 원인과 함께 적는다
- 확인: 스크립트 출력을 PR에 붙인다

#### 25 오라클 서버 배포

- 브랜치: `feature/25-deploy`
- 이슈 제목: 오라클 서버 배포
- 읽을 문서: 17.4, 17.5, 17.6
- 먼저 정할 것: 오라클 서버에 올릴지와 시기. 서버를 만들기 전에 무료 한도를 다시 확인한다
- 만드는 것
    - `docker-compose.oci.yml`(17.4의 2코어 상한), Caddy 설정
    - 배포 문서(`docs/DEPLOY-OCI.md`)
    - 2코어에서 다시 돌린 부하 실험 결과
- 끝났다고 보는 기준: 공개 주소에서 시연할 수 있다
- 확인: 서버 접속과 명령 실행은 사용자가 한다. 필요한 명령을 순서대로 알려 준다

#### 26 선택 과제: 로컬 쿠버네티스

- 브랜치: `feature/26-k3d`
- 이슈 제목: 선택 과제: 로컬 쿠버네티스
- 읽을 문서: 17.2
- 먼저 정할 것: 이 작업을 할지
- 만드는 것: 매니페스트, 준비 상태와 생존 상태 프로브, 무중단 교체 배포, 대기열 나이를 기준으로 한 worker 자동 확장
- 끝났다고 보는 기준: 로컬 k3d에서 전체가 뜨고 worker가 대기열 나이에 따라 늘어난다

## 5. 진행 체크

작업을 끝낼 때 해당 줄을 체크한다(1.7).

- [x] 01 골격과 문서
- [x] 02 봉투 형식과 DSN
- [x] 03 수집 엔드포인트와 한도
- [x] 04 부하 도구와 폭주 실험 1차
- [x] 05 정규화와 지문 규칙
- [x] 06 저장소 스키마
- [x] 07 worker 단계 구조와 역압
- [x] 08 이슈, 집계, 중복, 격리
- [x] 09 Starter SDK
- [x] 10 데모 앱
- [x] 11 로그인과 권한
- [x] 12 콘솔 뼈대와 이슈 화면
- [x] 13 소스맵 해석과 캐시
- [x] 14 Vue SDK
- [x] 15 릴리스와 소스맵 업로드, 재발 판정
- [x] 16 지표, 대시보드, 경보
- [x] 17 자원 실험
- [x] 18 추적 SDK
- [x] 19 추적 저장과 성능 화면
- [x] 20 업타임 점검
- [x] 21 알림
- [x] 22 통합 화면
- [x] 23 팬파라이브와 CoPage 연결 (로컬에서 확인: CoPage #38, 팬파라이브 #40. 서버 연결은 25 뒤)
- [x] 24 장애 실험
- [ ] 25 오라클 서버 배포 (설정과 절차 준비 완료: docker-compose.oci.yml, Caddy, DEPLOY-OCI.md. 서버 작업은 사용자가)
- [ ] 26 선택 과제: 로컬 쿠버네티스 (하지 않음, CHOICES 26-1)
