# ErrDeck

에러, 요청 성능, 가동 상태를 한곳에서 수집하는 모니터링 플랫폼이다. 감시 대상이 SDK로 보낸 이벤트를 ingest가 받아 대기열에 넣고, worker가 처리해 저장하고, api와 콘솔이 보여 준다.

Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다. 저장소는 PostgreSQL, Redis, ClickHouse다.

## 지금 상태

24 작업(장애 실험)까지 했다. 자원 실험과 장애 실험의 결과는 [docs/EXPERIMENTS.md](docs/EXPERIMENTS.md)에 있다. 18.2 의 부하 실험 중 남은 지속 부하 30분, 지문 다양성, 조회 간섭, 추적을 켠 SDK 부담도 4코어 맥에서 재서(29) 2.2 의 목표 중 처리량, 수집 응답 시간, 표시 지연, SDK 부담을 지킨 것을 확인했다. Redis 가 새 주소로 다시 떠도 ingest 와 worker 가 몇 초 안에 따라간다(30). 팬파라이브와 CoPage 는 같은 맥에서 SDK 를 붙여 에러, 추적, 요약 카드가 통합 화면에 보이는 것까지 확인했다(23, [docs/INTEGRATION.md](docs/INTEGRATION.md)). 오라클 배포(25)는 이 저장소 쪽 준비를 마쳤다([docs/DEPLOY-OCI.md](docs/DEPLOY-OCI.md)). core에 DSN 해석, 봉투 형식, 이벤트 모델, 지문 규칙 9개(값을 지운 메시지, 프록시와 람다 이름 정리, 앱 프레임 판정), 소스맵 해석기와 바이트 상한 캐시가 있다. ingest는 `POST /ingest/{projectId}/envelope` 로 봉투를 받아 키와 출처를 확인하고, Lua 스크립트 하나로 프로젝트 한도 차감, 대기열 길이 확인, Redis 스트림 적재를 한다. api는 콘솔 사용자의 가입, 로그인, 토큰 재발급과 시스템 단위 권한(VIEWER, MEMBER, ADMIN, OWNER)을 맡고, 시스템, 구성원, 프로젝트, DSN 키를 관리하는 API를 둔다. 뜰 때 PostgreSQL 스키마(Flyway, 테이블 16개)와 ClickHouse 스키마(테이블 5개, 분 단위 집계 뷰, worker와 api용 사용자)를 적용한다. loadgen으로 지속 부하와 폭주를 걸 수 있고, 폭주에서 대기열이 상한 20,000에서 멈추는 것을 확인했다([docs/EXPERIMENTS.md](docs/EXPERIMENTS.md)). worker는 대기열을 읽어 이벤트의 지문을 계산하고, 지문으로 이슈를 찾거나 만들고, 받은 이벤트 전부의 건수와 이슈별 분당 100건까지의 원본을 ClickHouse에 묶어 쓴 뒤 확인 처리한다. 이미 처리한 event_id는 거르고, 처리할 수 없는 원문은 격리한다. 모든 큐에 길이가 있어 ClickHouse가 멈추면 대기열이 상한에서 멈추고 ingest가 503을 준다. 브라우저 에러는 올린 소스맵으로 원본 파일과 줄로 되돌리고, 해결한 이슈가 나중 릴리스에서 다시 나면 재발로 바꾼다. sdk-spring은 감시 대상 Spring Boot 앱(서블릿, WebFlux)에서 500번대로 끝난 예외, 스케줄과 비동기 작업의 예외, `log.error` 의 예외를 잡아 묶어 보낸다. `docker compose --profile core --profile obs up -d --build` 한 번으로 세 프로세스와 Prometheus, Grafana, Alertmanager가 CPU와 메모리 상한 안에서 뜨고, 대시보드 5개와 경보 규칙 8개가 함께 올라온다. 두 SDK는 요청을 표본으로 추적해 `traceparent` 로 서비스 사이를 잇고, 콘솔의 성능 화면에서 엔드포인트 표(프로메테우스)와 느린 요청, 스팬 그림(반복 쿼리 표시)을 본다. worker는 업타임 모니터를 점검해(연속 2회로 다운과 복구 판정, 내부 주소 차단) 장애 기록을 남기고, 새 이슈, 재발, 빈도 초과, 다운, 인증서 만료, 자원 경보를 Slack과 Discord로 보낸다. 통합 화면은 시스템마다 상태 신호와 프로젝트별 요약 카드 6개를 보여 준다. 진행 상황은 [docs/WORKFLOW.md](docs/WORKFLOW.md)의 5장에 있다.

## 모듈

| 모듈 | 내용 | 포트 |
| --- | --- | --- |
| `core` | 도메인 모델, 지문 계산, 소스맵 해석, 봉투 형식. 스프링에 의존하지 않는다. 예제 봉투(`testFixtures`)를 다른 모듈의 테스트와 함께 쓴다 | |
| `ingest` | 수집 프로세스. 크기, DSN 키, 출처를 확인하고 한도 안에서 봉투를 Redis 스트림에 넣는다. 지표는 `/actuator/prometheus` | 8080 (compose 18080) |
| `worker` | 처리 프로세스. 대기열을 읽어 지문을 계산하고 이슈를 찾거나 만들며, 건수와 원본 표본을 ClickHouse에 묶어 쓴다. 웹 서버는 상태 확인과 지표에만 쓴다 | 8082 |
| `api` | 조회와 설정 프로세스. 로그인(JWT, 리프레시 쿠키), 시스템 단위 권한, 시스템과 프로젝트와 키 관리 API. PostgreSQL 스키마(Flyway)와 ClickHouse 스키마를 적용한다 | 8081 (compose 18081) |
| `sdk-spring` | Spring Boot Starter SDK. `errdeck.dsn` 만 설정하면 예외를 잡아 보낸다. 큐 1,000, 전송 스레드 1개, 같은 에러 접기, 거절 응답 따르기 | |
| `demo-api` | 데모 앱. Starter SDK를 붙이고 처리된 예외, 처리되지 않은 예외, 비동기, 스케줄, 400번대 예외를 내는 엔드포인트를 둔다 | 8090 |
| `loadgen` | 부하와 폭주를 만드는 명령줄 도구. 실제 봉투 형식으로 정해진 속도를 지켜 보내고(`sustained`, `burst`), 임의 주소에 GET을 보낸다(`http`). 응답 코드별 건수와 p50, p99를 출력한다 | |
| `web` | 콘솔 화면. Vue 3, Vite, Pinia. 로그인, 시스템과 프로젝트 사이드바, 이슈 목록과 상세 | 5173(개발 서버) |
| `sdk-vue` | Vue SDK. 실행 의존성 없음, gzip 3.8KB. 사전 요청 없이 묶어 보내고 탭을 떠날 때 sendBeacon | |
| `demo-web` | Vue SDK를 붙인 데모 화면. 에러 경로마다 버튼 하나 | 8092 |
| `deploy` | 운영 입구(Caddy), 감시 대상 서버의 프로메테우스 에이전트, compose가 쓰는 Prometheus 수집 설정과 경보 규칙, Alertmanager, Grafana 데이터 소스와 대시보드, ClickHouse 지표 설정 | |

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

프로세스를 띄운다. 스키마는 api가 적용하므로 api를 먼저 띄운다. 8081, 8082 포트를 다른 프로세스가 쓰고 있으면 `ERRDECK_API_PORT=18081`, `ERRDECK_WORKER_PORT=18082` 처럼 바꾼다. 포트는 `ERRDECK_INGEST_PORT`, `ERRDECK_API_PORT`, `ERRDECK_WORKER_PORT` 로 바꿀 수 있다.

```bash
./gradlew :api:bootRun
./gradlew :ingest:bootRun
curl -s http://localhost:8080/actuator/health
```

봉투를 보내 본다. 프로젝트와 키는 아직 화면이 없으므로 SQL로 만든다.

```bash
docker compose exec postgres psql -U errdeck -c "insert into systems (name) values ('local')"
docker compose exec postgres psql -U errdeck -c "insert into projects (system_id, slug, platform) values (1, 'local', 'spring')"
docker compose exec postgres psql -U errdeck -c "insert into project_keys (project_id, public_key) values (1, 'localkey')"
gzip -c core/src/testFixtures/resources/envelopes/example.envelope > /tmp/example.envelope.gz
curl -i -X POST -H 'X-ErrDeck-Key: localkey' --data-binary @/tmp/example.envelope.gz http://localhost:8080/ingest/1/envelope
docker compose exec redis redis-cli XLEN errdeck:stream:events
```

데모 앱으로 예외를 만들어 본다. ingest와 worker를 띄운 상태에서 데모 프로젝트를 넣고 데모 앱을 띄운다.

```bash
docker compose exec -T postgres psql -U errdeck < scripts/demo/seed.sql
./gradlew :demo-api:bootRun
curl -s localhost:8090/demo/handled/1      # 500, 이슈가 된다
curl -s localhost:8090/demo/bad-request    # 400, 이슈가 되지 않는다
```

콘솔 화면을 띄운다. 처음 가입한 사용자가 관리자가 된다. 데모 앱의 이슈를 보려면 그 사용자를 `데모 앱` 시스템의 구성원으로 넣는다.

```bash
cd web && npm ci && ERRDECK_API=http://localhost:8081 npm run dev   # http://localhost:5173
docker compose exec postgres psql -U errdeck -c "insert into system_members (system_id, user_id, role) select s.id, u.id, 'OWNER' from systems s, users u where s.name = '데모 앱' and u.email = '가입한 이메일'"
```

브라우저 앱의 소스맵을 올린다. 콘솔에서 프로젝트 토큰을 발급받아(`POST /api/projects/{id}/tokens`, OWNER) 빌드 뒤에 실행한다. 릴리스는 SDK 초기화에 넣은 값과 같아야 한다.

```bash
ERRDECK_TOKEN=edt_... scripts/upload-sourcemaps.sh http://localhost:8081 9002 demo-web@1a2b3c4 demo-web/dist
```

api와 worker는 같은 소스맵 볼륨(`ERRDECK_FILES_DIR`, 기본 `./data/artifacts`)을 써야 한다.

### compose로 전부 띄우기

```bash
docker compose --profile core --profile obs up -d --build
```

| 주소 | 내용 |
| --- | --- |
| http://localhost:18080 | ingest |
| http://localhost:18081 | api |
| http://localhost:13000 | Grafana(익명 보기, 관리자 admin/admin). 대시보드는 ErrDeck 폴더 |
| http://localhost:19090 | Prometheus |
| http://localhost:19093 | Alertmanager |

호스트 포트는 `ERRDECK_INGEST_HOST_PORT`, `ERRDECK_API_HOST_PORT`, `ERRDECK_GRAFANA_PORT`, `ERRDECK_PROMETHEUS_PORT`, `ERRDECK_ALERTMANAGER_PORT` 로 바꾼다. 프로세스의 지표는 컨테이너 안의 관리 포트 8091로만 나오고 호스트에 열지 않는다. 대시보드는 `deploy/grafana/generate.py` 가 만든다. 고칠 때는 이 파일을 고치고 다시 실행한다.

서비스 두 개를 지나는 요청을 보려면 데모 앱 둘을 같이 띄운다(`/demo/chain/{id}` 가 다른 데모 앱의 `/demo/items/{id}` 를 부르고, 그쪽은 반복 쿼리를 낸다).

```bash
./gradlew :demo-api:bootJar
DEMO_ERRDECK_DSN=http://demoapikey@ingest:8080/9001 docker compose --profile core --profile obs --profile demo up -d --build
curl localhost:8090/demo/chain/3
```

자원 실험은 `python3 scripts/experiments/resources.py <실험>` 이다. 실험 이름과 방법은 스크립트 첫머리에 있다.

데모 앱을 compose로 띄우려면 `./gradlew :demo-api:bootJar :loadgen:installDist` 뒤에 `docker compose --profile demo up -d demo-api` 를 쓴다.

## 문서

- [docs/README.md](docs/README.md): 기획과 설계 문서의 목차와 이름 규칙
- [docs/WORKFLOW.md](docs/WORKFLOW.md): 작업 절차와 작업 목록
- [docs/adr/](docs/adr/README.md): 결정 기록
- [docs/EXPERIMENTS.md](docs/EXPERIMENTS.md): 부하, 자원, 장애 실험의 결과
- [docs/CHOICES.md](docs/CHOICES.md): 작업 중에 추천안으로 정한 것과 그 이유. 나중에 검토할 목록

## 작업 방식

- 작업 하나는 이슈 하나, 브랜치 하나, PR 하나다. 작업 목록은 [docs/WORKFLOW.md](docs/WORKFLOW.md)의 4장에 있다.
- 브랜치는 develop에서 `feature/NN-이름` 으로 만든다. 번호는 작업마다 1씩 늘어난다.
- PR은 develop으로 보내고 본문에 `Closes #이슈번호` 를 적어 합치면 이슈가 닫히게 한다.
- develop과 main에 직접 푸시하지 않는다.
