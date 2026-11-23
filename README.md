# ErrDeck

에러, 요청 성능, 가동 상태를 한곳에서 수집하는 모니터링 플랫폼이다. 감시 대상이 SDK로 보낸 이벤트를 ingest가 받아 대기열에 넣고, worker가 처리해 저장하고, api와 콘솔이 보여 준다.

Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다. 저장소는 PostgreSQL, Redis, ClickHouse다.

## 지금 상태

05 작업(정규화와 지문 규칙)까지 했다. core에 DSN 해석, 봉투 형식, 이벤트 모델, 지문 규칙 9개(값을 지운 메시지, 프록시와 람다 이름 정리, 앱 프레임 판정)가 있다. ingest는 `POST /ingest/{projectId}/envelope` 로 봉투를 받아 키와 출처를 확인하고, Lua 스크립트 하나로 프로젝트 한도 차감, 대기열 길이 확인, Redis 스트림 적재를 한다. api는 PostgreSQL 스키마(Flyway)를 적용한다. loadgen으로 지속 부하와 폭주를 걸 수 있고, 폭주에서 대기열이 상한 20,000에서 멈추는 것을 확인했다([docs/EXPERIMENTS.md](docs/EXPERIMENTS.md)). worker는 아직 대기열을 읽지 않는다. 진행 상황은 [docs/WORKFLOW.md](docs/WORKFLOW.md)의 5장에 있다.

## 모듈

| 모듈 | 내용 | 포트 |
| --- | --- | --- |
| `core` | 도메인 모델, 지문 계산, 소스맵 해석, 봉투 형식. 스프링에 의존하지 않는다. 예제 봉투(`testFixtures`)를 다른 모듈의 테스트와 함께 쓴다 | |
| `ingest` | 수집 프로세스. 크기, DSN 키, 출처를 확인하고 한도 안에서 봉투를 Redis 스트림에 넣는다. 지표는 `/actuator/prometheus` | 8080 |
| `worker` | 처리 프로세스. 웹 서버는 상태 확인과 지표에만 쓴다 | 8082 |
| `api` | 조회와 설정 프로세스. PostgreSQL 스키마(Flyway)를 적용한다 | 8081 |
| `sdk-spring` | Spring Boot Starter SDK | |
| `loadgen` | 부하와 폭주를 만드는 명령줄 도구. 실제 봉투 형식으로 정해진 속도를 지켜 보내고 응답 코드별 건수와 p50, p99를 출력한다 | |

`sdk-vue`(Vue SDK)와 `web`(콘솔 화면)은 뒤 작업에서 더한다.

## 빌드와 실행

JDK 25와 Docker가 필요하다.

```bash
./gradlew build
```

로컬 저장소를 띄운다. 포트는 PostgreSQL 15432, Redis 16379, ClickHouse 18123(HTTP)이고 계정은 모두 `errdeck` / `errdeck` 이다.

```bash
docker compose up -d
docker compose ps
```

프로세스를 띄운다. PostgreSQL 스키마는 api가 적용하므로 api를 먼저 띄운다. 포트는 `ERRDECK_INGEST_PORT`, `ERRDECK_API_PORT`, `ERRDECK_WORKER_PORT` 로 바꿀 수 있다.

```bash
./gradlew :api:bootRun
./gradlew :ingest:bootRun
curl -s http://localhost:8080/actuator/health
```

봉투를 보내 본다. 프로젝트와 키는 아직 화면이 없으므로 SQL로 만든다.

```bash
docker compose exec postgres psql -U errdeck -c "insert into projects (system_id, slug, platform) values (1, 'local', 'spring')"
docker compose exec postgres psql -U errdeck -c "insert into project_keys (project_id, public_key) values (1, 'localkey')"
gzip -c core/src/testFixtures/resources/envelopes/example.envelope > /tmp/example.envelope.gz
curl -i -X POST -H 'X-ErrDeck-Key: localkey' --data-binary @/tmp/example.envelope.gz http://localhost:8080/ingest/1/envelope
docker compose exec redis redis-cli XLEN errdeck:stream:events
```

## 문서

- [docs/README.md](docs/README.md): 기획과 설계 문서의 목차와 이름 규칙
- [docs/WORKFLOW.md](docs/WORKFLOW.md): 작업 절차와 작업 목록
- [docs/adr/](docs/adr/README.md): 결정 기록
- [docs/EXPERIMENTS.md](docs/EXPERIMENTS.md): 부하, 자원, 장애 실험의 결과

## 작업 방식

- 작업 하나는 이슈 하나, 브랜치 하나, PR 하나다. 작업 목록은 [docs/WORKFLOW.md](docs/WORKFLOW.md)의 4장에 있다.
- 브랜치는 develop에서 `feature/NN-이름` 으로 만든다. 번호는 작업마다 1씩 늘어난다.
- PR은 develop으로 보내고 본문에 `Closes #이슈번호` 를 적어 합치면 이슈가 닫히게 한다.
- develop과 main에 직접 푸시하지 않는다.
