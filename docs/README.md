# ErrDeck 문서

에러, 요청 성능, 가동 상태를 한곳에서 수집하는 모니터링 플랫폼의 기획과 설계 문서다. Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다.

작업을 시작할 때는 [WORKFLOW.md](./WORKFLOW.md)를 먼저 읽는다. 작업 하나를 진행하는 절차와 작업 목록 26개가 거기에 있다.

문서 안에서 "5.4", "14장" 같은 표현은 아래 표의 장 번호를 가리킨다.

| 장 | 파일 | 내용 |
| --- | --- | --- |
| 1 | [01-PLAN.md](./01-PLAN.md) | 개요와 핵심 결정 |
| 2 | [01-PLAN.md](./01-PLAN.md) | 기능 범위, 비기능 목표 7개, 보존 기간 |
| 3 | [01-PLAN.md](./01-PLAN.md) | 용어, 시스템 4개와 프로젝트 13개 |
| 4 | [02-ARCHITECTURE.md](./02-ARCHITECTURE.md) | 프로세스 3개, 이벤트가 지나는 길, 코드 구조, 버린 대안 |
| 5 | [03-INGEST.md](./03-INGEST.md) | DSN, 봉투 형식, ingest의 다섯 단계, 한도, 폭주 대응 |
| 6 | [04-PIPELINE.md](./04-PIPELINE.md) | 처리 단계, 지문 규칙 9개, 이슈 상태, 전달 보장 |
| 7 | [05-SOURCEMAP.md](./05-SOURCEMAP.md) | 소스맵 업로드, 해석, 캐시 |
| 8 | [06-TRACING.md](./06-TRACING.md) | 추적 모델, 전파, 계측 범위, 표본 추출 |
| 9 | [07-UPTIME.md](./07-UPTIME.md) | 모니터, 실행 구조, 판정, 내부 주소 차단 |
| 10 | [08-DATA-MODEL.md](./08-DATA-MODEL.md) | PostgreSQL 테이블 16개, ClickHouse 테이블 5개, Redis 키 7개 |
| 11 | [09-SDK.md](./09-SDK.md) | SDK 공통 규칙, Spring Boot Starter, Vue SDK |
| 12 | [10-CONSOLE.md](./10-CONSOLE.md) | 통합 화면, 요약 카드 지표 6개, 그라파나 연결 |
| 13 | [11-OBSERVABILITY.md](./11-OBSERVABILITY.md) | 프로메테우스와 그라파나, 직접 만드는 지표 12개, 대시보드 5개, 경보 8개 |
| 14 | [12-RESOURCES.md](./12-RESOURCES.md) | CPU 배분, 스레드 모델, 역압, 커넥션 풀, 메모리 예산, GC |
| 15 | [13-SECURITY.md](./13-SECURITY.md) | 주체 4종의 인증, 필터 체인 3개, 권한, 민감한 정보 지우기 |
| 16 | [14-ALERTS.md](./14-ALERTS.md) | 알림 규칙 7종, 폭주 억제, 발송 |
| 17 | [15-DEPLOY.md](./15-DEPLOY.md) | 로컬 compose, 오라클 무료 서버, 쿠버네티스 비교 |
| 18 | [16-TEST-PLAN.md](./16-TEST-PLAN.md) | 테스트, 부하 실험 6종, 자원 실험 7종, 장애 실험 6종 |
| 19 | [17-ROADMAP.md](./17-ROADMAP.md) | 8단계 24주 일정 |
| 20 | [18-RISKS.md](./18-RISKS.md) | 위험 8개, 정해야 할 것, ADR 목록 |
| | [adr/](./adr/README.md) | 결정 13개와 버린 대안 |
| | [WORKFLOW.md](./WORKFLOW.md) | 작업 절차와 작업 목록 26개 |
| | [CHOICES.md](./CHOICES.md) | 작업 중에 Claude가 추천안으로 정한 것. 나중에 검토하고 리팩토링할 목록 |
| | [EXPERIMENTS.md](./EXPERIMENTS.md) | 부하, 자원, 장애 실험의 실측값 |

## 읽는 순서

1. 처음이면 [01-PLAN.md](./01-PLAN.md)의 1장과 [02-ARCHITECTURE.md](./02-ARCHITECTURE.md)를 먼저 읽는다.
2. 작업을 시작할 때는 [WORKFLOW.md](./WORKFLOW.md)에서 그 작업의 "읽을 문서"를 따라간다.
3. 서버와 인프라 설계의 근거는 [12-RESOURCES.md](./12-RESOURCES.md)와 [11-OBSERVABILITY.md](./11-OBSERVABILITY.md)에 모여 있다.
4. 설계를 바꿀 때는 해당 장을 고치고 [adr/](./adr/README.md)에 결정을 남긴다.

## 이름 규칙

코드, 설정, 지표의 이름을 한 가지 방식으로 맞춘다.

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 자바 패키지 | `com.errdeck.<모듈>` | `com.errdeck.core`, `com.errdeck.ingest` |
| 스프링 애플리케이션 이름 | `errdeck-<프로세스>` | `errdeck-ingest` |
| 설정 접두사 | `errdeck.` | `errdeck.dsn`, `errdeck.traces.sample-rate` |
| 환경 변수 | `ERRDECK_` | `ERRDECK_DB_PORT` |
| DSN 키 헤더 | `X-ErrDeck-Key` | |
| Redis 키 | `errdeck:` | `errdeck:stream:events`, `errdeck:rl:{projectId}` |
| 프로메테우스 지표 | `errdeck_` | `errdeck_queue_oldest_age_seconds` |
| SDK 진입점 | `ErrDeck` | `ErrDeck.capture(e)` |
| 브랜치 | `feature/NN-이름` | `feature/02-envelope` |

## 아직 비어 있는 것

- 비기능 목표(2.2)의 실측값. 04, 17, 24 작업에서 잰다.
- `EXPERIMENTS.md`: 부하, 자원, 장애 실험의 결과. 04 작업에서 만들고 17, 24 작업에서 채운다. 실행하지 않은 실험의 칸은 비워 둔다.
- 화면 시안. 12 작업을 시작할 때 시안을 먼저 만들어 고른다.
