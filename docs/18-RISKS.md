# 20. 위험, 미정 사항, ADR 목록

## 20.1 위험

| 위험 | 영향 | 대응 |
| --- | --- | --- |
| 범위가 넓다. 에러, 성능, 업타임 세 가지를 모두 한다 | 어느 것도 깊지 못하게 끝남 | 에러 경로(1단계에서 4단계)를 먼저 완성한다. 일정이 밀리면 6단계의 성능 추적을 줄인다 |
| 지문 규칙의 품질 | 이슈가 쪼개지거나 뭉쳐 서비스 전체의 신뢰가 떨어짐 | 규칙에 버전을 두고 사례 테스트로 고정. 병합과 분리 기능으로 사람이 바로잡을 수 있게 함 |
| 소스맵 해석기의 정확성 | 틀린 위치를 보여 주는 것은 안 보여 주는 것보다 나쁨 | 기존 라이브러리와의 대조 테스트. 시간이 모자라면 색인 소스맵 같은 드문 형식은 지원하지 않음 |
| ClickHouse를 작은 메모리로 운영 | 병합이나 조회 중 메모리 초과 | 서버와 쿼리의 메모리 상한 설정. 5단계에서 실측. 안 되면 예산을 다시 나눔 |
| SDK가 감시 대상에 영향 | 팬파라이브나 CoPage의 장애 원인이 됨 | 11.1의 규칙과 18.4의 전체 정지 실험. 설정 하나로 SDK를 끌 수 있게 함 |
| Redis Streams의 한계 | 목표 처리량에 못 미침 | 18.2에서 확인. 대기열 접근을 포트 뒤에 두어 교체할 수 있게 함 |
| 오라클 무료 정책 변경 | 공개 시연 환경이 사라짐 | 로컬을 기준 환경으로 유지. 시연 영상을 남김 |
| 팬파라이브 gateway가 WebFlux 기반일 가능성 | 서블릿용 SDK가 붙지 않음 | 확인 후 필요하면 WebFilter 구현 추가(11.2) |

## 20.2 정해야 할 것

- [x] 정함(2026-11-23): 서비스 이름 ErrDeck, 저장소 dekim2018/ErrDeck, 기본 패키지 com.errdeck
- [ ] 시작일
- [ ] 팬파라이브의 gateway를 프로젝트로 등록할지(3.2). gateway는 WebFlux 기반으로 확인했다
- [ ] 콘솔 화면 시안. 3단계 첫 주에 시안을 만들어 고른다
- [ ] SDK 패키지를 올릴 곳. 처음에는 로컬 저장소로 충분하고, 팬파라이브에 붙이는 7단계 전에 정하면 된다
- [x] 정함(2026-11-23): 작업 방식은 CoPage와 같게 한다. 저장소의 docs/WORKFLOW.md에 정리(이슈 먼저, `feature/NN-이름` 브랜치, develop으로 PR)
- [ ] 오라클 서버에 올릴지와 시기
- [ ] 알림 채널. Slack과 Discord 중 무엇을 먼저 할지
- [ ] 쿠버네티스 선택 과제를 할지

## 20.3 ADR 목록

결정 13개는 [adr/](./adr/README.md)에 파일 하나씩으로 있다. 각 문서는 고른 것, 버린 대안, 이유, 감수하는 것, 측정할 값을 적는다.

| 번호 | 결정 | 근거 장 |
| --- | --- | --- |
| [0001](./adr/0001-three-processes-by-load.md) | 프로세스를 도메인이 아니라 부하 성격으로 셋으로 나눈다 | 4.2 |
| [0002](./adr/0002-bounded-redis-streams.md) | 대기열은 길이 상한이 있는 Redis Streams를 쓴다 | 4.5, 5.5 |
| [0003](./adr/0003-ingest-does-not-parse.md) | ingest는 본문을 해석하지 않고 Redis 왕복 한 번으로 끝낸다 | 5.4 |
| [0004](./adr/0004-exact-counts-bounded-samples.md) | 건수는 정확하게, 원본은 제한해서 저장한다 | 5.1, 10.3 |
| [0005](./adr/0005-events-in-clickhouse-issues-in-postgres.md) | 이벤트는 ClickHouse, 이슈는 PostgreSQL에 둔다 | 3.3 |
| [0006](./adr/0006-link-by-fingerprint-not-issue-id.md) | 이벤트에 issue_id를 넣지 않고 지문으로 잇는다 | 6.4 |
| [0007](./adr/0007-sourcemap-parser-in-java.md) | 소스맵 해석기를 Java로 직접 구현하고 배열로 보관한다 | 7.3 |
| [0008](./adr/0008-metrics-for-stats-traces-for-causes.md) | 성능 통계는 프로메테우스, 원인 분석은 표본 추적으로 나눈다 | 8.1 |
| [0009](./adr/0009-virtual-threads-only-for-waiting.md) | 가상 스레드는 기다리는 일에만 쓰고 상한을 직접 만든다 | 14.4 |
| [0010](./adr/0010-bounded-queues-backpressure.md) | 모든 대기 공간에 길이를 두어 역압을 SDK까지 전한다 | 14.5 |
| [0011](./adr/0011-summary-cards-and-grafana-links.md) | 지표는 요약 카드만 화면에 올리고 상세는 그라파나로 잇는다 | 12.1 |
| [0012](./adr/0012-remote-write-from-agents.md) | 다른 서버의 지표는 에이전트가 밀어 보낸다 | 13.3 |
| [0013](./adr/0013-compose-first-kubernetes-optional.md) | 배포는 compose를 기준으로 하고 쿠버네티스는 선택 과제로 둔다 | 17.2 |

## 20.4 참고한 자료

- [오라클 클라우드 Always Free 자원 문서](https://docs.oracle.com/iaas/Content/FreeTier/resourceref.htm): 17.4의 무료 한도와 유휴 회수 조건
- [InfoQ, 오라클 무료 한도 변경 기사(2026년 7월 3일)](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/): 17.4의 한도 변경 경위
