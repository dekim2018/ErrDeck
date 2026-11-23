# 결정 기록 (ADR)

결정 하나에 파일 하나를 둔다. 결정을 바꿀 때는 기존 파일을 지우지 않고 상태를 "대체됨"으로 바꾼 뒤 새 번호로 추가한다.

각 문서의 "관련 문서"에 적힌 장 번호는 [../README.md](../README.md)의 표에 있는 장 번호다.

| 번호 | 결정 | 버린 대안 | 결정일 |
| --- | --- | --- | --- |
| [0001](./0001-three-processes-by-load.md) | 프로세스를 부하 성격으로 셋으로 나눈다 | 프로세스 하나에 전부 넣기, 도메인별 MSA(프로젝트 서비스, 이슈 서비스, 알림 서비스) | 2026-11-23 |
| [0002](./0002-bounded-redis-streams.md) | 대기열은 길이 상한이 있는 Redis Streams를 쓴다 | Kafka, PostgreSQL 테이블을 대기열로 쓰기, ingest가 ClickHouse에 바로 쓰기 | 2026-11-23 |
| [0003](./0003-ingest-does-not-parse.md) | ingest는 본문을 해석하지 않고 Redis 왕복 한 번으로 끝낸다 | ingest에서 압축을 풀어 내용 검증, 프로젝트마다 대기열 하나, 로컬 디스크에 쓰고 worker가 읽기 | 2026-11-23 |
| [0004](./0004-exact-counts-bounded-samples.md) | 건수는 정확하게, 원본은 제한해서 저장한다 | 받은 이벤트 원본을 전부 저장, 폭주 때 초과분을 조용히 버리기 | 2026-11-23 |
| [0005](./0005-events-in-clickhouse-issues-in-postgres.md) | 이벤트는 ClickHouse, 이슈는 PostgreSQL에 둔다 | 전부 PostgreSQL, 전부 ClickHouse | 2026-11-23 |
| [0006](./0006-link-by-fingerprint-not-issue-id.md) | 이벤트에 issue_id를 넣지 않고 지문으로 잇는다 | 이벤트에 issue_id 저장 | 2026-11-23 |
| [0007](./0007-sourcemap-parser-in-java.md) | 소스맵 해석기를 Java로 직접 구현하고 배열로 보관한다 | 브라우저에서 복원, Node 프로세스를 옆에 띄워 기존 라이브러리 사용, 조회할 때만 복원, 구간을 객체 목록으로 보관 | 2026-11-23 |
| [0008](./0008-metrics-for-stats-traces-for-causes.md) | 성능 통계는 프로메테우스, 원인 분석은 표본 추적으로 나눈다 | 전체 요청의 스팬 저장, 표본으로 p95 계산, 서버에서 추적 전체를 받은 뒤 고르기, OpenTelemetry 에이전트 사용 | 2026-11-23 |
| [0009](./0009-virtual-threads-only-for-waiting.md) | 가상 스레드는 기다리는 일에만 쓰고 상한을 직접 만든다 | 모든 일을 가상 스레드로, 공용 포크조인 풀과 병렬 스트림, 큰 스레드 풀 하나를 여러 용도로 공유 | 2026-11-23 |
| [0010](./0010-bounded-queues-backpressure.md) | 모든 대기 공간에 길이를 두어 역압을 SDK까지 전한다 | 길이 제한 없는 큐, 막히면 오래된 것부터 조용히 버리기 | 2026-11-23 |
| [0011](./0011-summary-cards-and-grafana-links.md) | 지표는 요약 카드만 화면에 올리고 상세는 그라파나로 잇는다 | 지표 차트를 콘솔에 직접 구현, 지표는 그라파나 링크만 제공 | 2026-11-23 |
| [0012](./0012-remote-write-from-agents.md) | 다른 서버의 지표는 에이전트가 밀어 보낸다 | 중앙에서 인터넷으로 Actuator를 직접 수집 | 2026-11-23 |
| [0013](./0013-compose-first-kubernetes-optional.md) | 배포는 compose를 기준으로 하고 쿠버네티스는 선택 과제로 둔다 | 처음부터 쿠버네티스(k3s) | 2026-11-23 |
