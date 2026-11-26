# 10. 저장소 설계

저장소는 세 개를 쓰고 역할이 겹치지 않는다. PostgreSQL은 고쳐 쓰는 메타데이터, ClickHouse는 쌓기만 하는 대량 기록, Redis는 잠깐 머무는 것(대기열, 한도, 캐시)을 맡는다.

## 10.1 PostgreSQL

테이블은 16개다. 접근은 JdbcClient로 하고 스키마는 Flyway로 관리한다.

| 테이블 | 주요 열과 제약 | 비고 |
| --- | --- | --- |
| users | email 유일, password_hash, role | 콘솔 사용자 |
| systems | name 유일 | 프로젝트 묶음 |
| projects | system_id, slug 유일, platform, rate_limit_per_min, allowed_origins | platform은 spring 또는 vue |
| system_members | PK(system_id, user_id), role | 권한은 시스템 단위로 줌 |
| project_keys | public_key 유일, project_id, revoked_at | DSN 키 |
| api_tokens | token_hash 유일, project_id, scope, last_used_at | 소스맵 업로드용. 원문은 저장하지 않음 |
| releases | 유일(project_id, version), created_at | created_at이 재발 판정의 순서 기준 |
| release_artifacts | 유일(release_id, name), size, sha256, path | 소스맵 파일 |
| issues | project_id, status, title, culprit, first_seen, last_seen, resolved_in_release, assignee_id | 목록 정렬용 색인 (project_id, status, last_seen DESC) |
| issue_fingerprints | PK(project_id, fingerprint), issue_id, rule_version | 지문과 이슈의 대응 |
| issue_comments | issue_id, user_id, body |  |
| quarantined_events | project_id, raw, reason, created_at | 처리 실패한 원문. 7일 뒤 삭제 |
| alert_rules | project_id, kind, threshold, window, channel, cooldown |  |
| notifications | 유일(rule_id, dedupe_key), sent_at, status | 보낸 알림 기록이자 중복 발송 방지 |
| monitors | project_id, url, interval, status, paused_until |  |
| monitor_incidents | monitor_id, started_at, ended_at, cause |  |

## 10.2 ClickHouse 테이블

| 테이블 | 엔진 | 정렬 키 | 보존 |
| --- | --- | --- | --- |
| events | ReplacingMergeTree | (project_id, fingerprint, timestamp, event_id) | 30일 |
| event_ticks | Null | 없음. 저장하지 않고 아래 집계로 흘려보냄 | 없음 |
| issue_stats_1m | AggregatingMergeTree | (project_id, fingerprint, minute) | 1년 |
| transactions | MergeTree | (project_id, name, timestamp) | 7일 |
| uptime_checks | MergeTree | (monitor_id, timestamp) | 90일 |

정렬 키는 가장 자주 쓰는 조회에 맞춘다. 이슈 상세 화면은 한 프로젝트, 한 지문의 최근 이벤트를 읽는다. 정렬 키의 앞 세 열이 그 조건과 같으므로 해당 구간만 읽는다.

```sql
CREATE TABLE events (
  project_id    UInt32,
  event_id      UUID,
  fingerprint   FixedString(32),
  timestamp     DateTime64(3),
  received_at   DateTime64(3),
  environment   LowCardinality(String),
  release       LowCardinality(String),
  level         LowCardinality(String),
  exc_type      LowCardinality(String),
  message       String,
  user_hash     UInt64,
  trace_id      FixedString(32),
  tags          Map(LowCardinality(String), String),
  restored      UInt8,
  payload       String CODEC(ZSTD(3))
)
ENGINE = ReplacingMergeTree
PARTITION BY toDate(timestamp)
ORDER BY (project_id, fingerprint, timestamp, event_id)
TTL toDate(timestamp) + INTERVAL 30 DAY
SETTINGS ttl_only_drop_parts = 1;
```

설계 판단은 네 가지다.

- **하루 단위 파티션과 통째 삭제**: 30일이 지난 데이터는 행을 골라 지우지 않고 그날의 파티션을 통째로 버린다. 삭제 비용이 거의 없다.
- **값의 종류가 적은 열은 LowCardinality**: 환경, 릴리스, 예외 타입은 값이 수십 종이다. 사전으로 압축되어 디스크와 필터 속도에 유리하다.
- **원본은 payload 한 열에**: 스택 프레임, 요청 정보, 빵부스러기는 조건 검색에 쓰지 않는다. JSON 그대로 압축해 한 열에 두고, 검색에 쓰는 값만 별도 열로 뺀다.
- **사용자 식별자는 해시**: 영향 사용자 수를 세는 데는 같은 사람인지만 알면 된다. 원래 값은 payload 안에만 둔다.

## 10.3 건수 집계의 흐름

5.1의 원칙(건수는 정확하게, 원본은 제한해서)을 테이블 구조로 구현한다.

1. worker는 받은 이벤트 전부를 `event_ticks` 에 넣는다. 한 줄은 프로젝트, 지문, 시각, 횟수, 사용자 해시뿐이라 작다.
2. `event_ticks` 는 Null 엔진이라 아무것도 저장하지 않는다. 대신 여기에 걸린 구체화 뷰가 분 단위로 합쳐 `issue_stats_1m` 에 넣는다.
3. worker는 이슈별 상한 안에 든 이벤트만 `events` 에 넣는다.

```sql
CREATE MATERIALIZED VIEW issue_stats_1m_mv TO issue_stats_1m AS
SELECT
  project_id,
  fingerprint,
  toStartOfMinute(timestamp) AS minute,
  sum(count)                 AS times,
  uniqState(user_hash)       AS users
FROM event_ticks
GROUP BY project_id, fingerprint, minute;
```

이슈 목록의 횟수, 그래프, 알림의 빈도 판정은 전부 `issue_stats_1m` 을 읽는다. 원본 테이블을 세지 않으므로 폭주 때 원본을 줄여도 숫자가 변하지 않는다.

## 10.4 쓰기 규칙: 작게 여러 번 쓰지 않는다

ClickHouse는 삽입 한 번마다 파트라는 파일 묶음을 만들고, 뒤에서 합친다. 이벤트마다 삽입하면 파트가 합치는 속도보다 빨리 늘어나 쓰기가 거부된다.

worker는 테이블별로 모았다가 1초가 지났거나 5,000행이 모이면 한 번에 쓴다. 쓰기 스레드는 하나만 둔다. 삽입이 초당 몇 번을 넘지 않게 된다.

| 대안 | 버린 이유 |
| --- | --- |
| 이벤트마다 삽입 | 파트 폭증 |
| 서버의 비동기 삽입 기능에 맡기기 | 동작은 하지만, 묶음 크기와 쓰기 완료 시점을 worker가 쥐고 있어야 대기열 확인 처리와 순서를 맞추기 쉬움 |
| 트랜잭션의 스팬을 별도 테이블에 한 행씩 | 행 수가 수십 배. 스팬은 트랜잭션 행 안에 배열 열로 넣음. 추적 조회는 trace_id에 건너뛰기 색인을 둠 |

## 10.5 조회 보호

콘솔의 쿼리가 수집을 방해하지 못하게 한다.

- api가 만드는 모든 쿼리에 프로젝트 조건과 기간 조건을 강제한다. 기간은 최대 30일이다.
- 쿼리별 실행 시간 상한(10초)과 메모리 상한을 ClickHouse 사용자 설정으로 건다. worker와 api는 서로 다른 ClickHouse 사용자를 쓴다.
- 그래프는 원본이 아니라 분 단위 집계를 읽는다.
- 서버 전체 메모리 상한, 캐시 크기, 쿼리당 스레드 수를 서버 1대에 맞게 줄인다. 값은 14장의 예산을 따른다.

## 10.6 Redis

| 키 | 자료형 | 용도 | 만료 |
| --- | --- | --- | --- |
| `errdeck:stream:events` | 스트림 | 에러 대기열 | 길이 상한 |
| `errdeck:stream:tx` | 스트림 | 트랜잭션 대기열 | 길이 상한 |
| `errdeck:stream:notify` | 스트림 | 보낼 알림 | 길이 상한 |
| `errdeck:rl:{projectId}` | 해시 | 토큰 버킷(남은 토큰, 마지막 채운 시각) | 2분 |
| `errdeck:seen:{대기열}:{항목 ID}` | 문자열 | 처리한 대기열 항목 표시(29 작업에서 이벤트 단위에서 바꿈, 6.7) | 10분 |
| `errdeck:monitors:due` | 정렬 집합 | 모니터의 다음 실행 시각 | 없음 |
| `errdeck:refresh:{tokenId}` | 문자열 | 리프레시 토큰 | 14일 |

Redis의 디스크 기록은 1초마다 하는 방식(AOF everysec)으로 둔다. Redis가 갑자기 죽으면 대기열의 마지막 1초 분량을 잃을 수 있다. 에러 이벤트는 결제 기록이 아니므로 이 정도 손실은 받아들이고, 대신 쓰기마다 디스크를 기다리는 비용을 피한다.

## 10.7 측정할 값

디스크 사용량은 미리 계산하지 않고 잰다. 하루 이벤트 수, 이벤트당 압축 후 크기, 파티션별 크기를 18장의 실험에서 기록해 30일 치 필요 용량을 구한다. 그 밖에 묶음 쓰기 시간, 활성 파트 수, 쿼리 시간 분포를 본다.

## 구현하면서 바뀐 점

- **PostgreSQL 스키마는 api가 소유한다**(03 작업): Flyway 스크립트는 `api/src/main/resources/db/migration` 에 두고 api가 뜰 때 적용한다. ingest와 worker는 Flyway를 돌리지 않는다. 그래서 로컬에서는 api를 한 번 띄운 뒤에 ingest와 worker를 띄운다. ingest의 통합 테스트는 같은 스크립트를 테스트 컨테이너에 적용한다.
- **projects 테이블의 system_id 외래 키**: 03 작업에서는 systems 테이블이 없어 외래 키 없이 만들었다. 06 작업에서 systems를 만들며 건다.
- **지문 열의 값**(05 작업): `fingerprint FixedString(32)` 와 PostgreSQL의 지문 열에는 SHA-256의 앞 16바이트를 소문자 16진수 32자로 넣는다(6장 끝).
- **ClickHouse 스키마도 api가 적용한다**(06 작업): 번호 붙인 SQL 파일은 `api/src/main/resources/clickhouse` 에 두고, api가 뜰 때 실행기가 순서대로 실행해 `schema_migrations` 에 번호를 남긴다. 관리 사용자(`errdeck`)로 실행하고, worker와 api는 각자의 사용자(`errdeck_worker`, `errdeck_api`)를 쓴다. 비밀번호는 SQL 파일에 두지 않고 설정(`ERRDECK_CLICKHOUSE_WORKER_PASSWORD`, `ERRDECK_CLICKHOUSE_API_PASSWORD`)에서 채운다.
- **조회 상한의 값**: api 사용자는 읽기 전용(`readonly = 2`)이고 쿼리별 실행 시간 10초, 메모리 512MB, 스레드 2개다. worker 사용자는 실행 시간 10초, 메모리 512MB다. 둘 다 ClickHouse 메모리 상한 2,048MB(14.7) 안에 들도록 잡은 처음 값이다. 서버 전체 메모리 상한과 캐시 크기는 컨테이너 상한을 거는 16 작업에서 정한다.
- **issue_stats_1m 의 열 타입**: `times` 는 `SimpleAggregateFunction(sum, UInt64)`, `users` 는 `AggregateFunction(uniq, UInt64)` 다. 구체화 뷰는 `count` 를 UInt64로 바꿔 더한다. 1년 보존이라 파티션은 월 단위로 둔다.
- **transactions 의 스팬 열**: 스팬은 같은 행의 배열 열 7개(`span_ids`, `span_parent_ids`, `span_ops`, `span_descriptions`, `span_starts_ms`, `span_durations_ms`, `span_repeats`)로 둔다. 계측을 만드는 18, 19 작업에서 다시 맞춘다.
- **PostgreSQL 테이블에서 정한 것**: 이슈 상태는 `unresolved`, `resolved`, `ignored`, `regressed`. 무시 조건은 `ignored_until` 과 `ignored_until_count`. 알림 규칙의 기간은 `window` 가 예약어라 `window_minutes` 로 쓴다. 모니터마다 열린 장애 기록은 부분 유일 색인으로 하나만 둔다.
- **처리 표시 키**(29 작업, 사용자 확인): `errdeck:seen:{eventId}` 1시간을 `errdeck:seen:{대기열}:{항목 ID}` 10분으로 바꿨다. 이벤트마다 1시간 남기면 폭주 몇 분 만에 Redis 데이터 상한 256MB를 채웠다. 이유와 감수하는 것은 6.7 끝에 있다.
