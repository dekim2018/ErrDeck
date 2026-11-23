# ErrDeck

에러, 요청 성능, 가동 상태를 한곳에서 수집하는 모니터링 플랫폼이다. 감시 대상이 SDK로 보낸 이벤트를 ingest가 받아 대기열에 넣고, worker가 처리해 저장하고, api와 콘솔이 보여 준다.

Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다. 저장소는 PostgreSQL, Redis, ClickHouse다.

## 지금 상태

01 작업(골격과 문서)까지 했다. 모듈 6개의 빈 골격과 로컬 저장소 compose, CI가 있다. ingest, worker, api는 뜨고 상태 확인에 응답하지만 아직 기능은 없다. 진행 상황은 [docs/WORKFLOW.md](docs/WORKFLOW.md)의 5장에 있다.

## 모듈

| 모듈 | 내용 | 포트 |
| --- | --- | --- |
| `core` | 도메인 모델, 지문 계산, 소스맵 해석, 봉투 형식. 스프링에 의존하지 않는다 | |
| `ingest` | 수집 프로세스. 봉투를 검사해 대기열에 넣는다 | 8080 |
| `worker` | 처리 프로세스. 웹 서버는 상태 확인과 지표에만 쓴다 | 8082 |
| `api` | 조회와 설정 프로세스 | 8081 |
| `sdk-spring` | Spring Boot Starter SDK | |
| `loadgen` | 부하와 폭주를 만드는 명령줄 도구 | |

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

프로세스를 띄운다. 포트는 `ERRDECK_INGEST_PORT`, `ERRDECK_API_PORT`, `ERRDECK_WORKER_PORT` 로 바꿀 수 있다.

```bash
./gradlew :ingest:bootRun
curl -s http://localhost:8080/actuator/health
```

## 문서

- [docs/README.md](docs/README.md): 기획과 설계 문서의 목차와 이름 규칙
- [docs/WORKFLOW.md](docs/WORKFLOW.md): 작업 절차와 작업 목록
- [docs/adr/](docs/adr/README.md): 결정 기록

## 작업 방식

- 작업 하나는 이슈 하나, 브랜치 하나, PR 하나다. 작업 목록은 [docs/WORKFLOW.md](docs/WORKFLOW.md)의 4장에 있다.
- 브랜치는 develop에서 `feature/NN-이름` 으로 만든다. 번호는 작업마다 1씩 늘어난다.
- PR은 develop으로 보내고 본문에 `Closes #이슈번호` 를 적어 합치면 이슈가 닫히게 한다.
- develop과 main에 직접 푸시하지 않는다.
