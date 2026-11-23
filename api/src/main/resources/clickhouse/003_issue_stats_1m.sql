-- 이슈별 분 단위 집계(10.3). 이슈 목록의 횟수, 그래프, 빈도 알림이 모두 여기를 읽는다.
CREATE TABLE IF NOT EXISTS issue_stats_1m (
  project_id    UInt32,
  fingerprint   FixedString(32),
  minute        DateTime,
  times         SimpleAggregateFunction(sum, UInt64),
  users         AggregateFunction(uniq, UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(minute)
ORDER BY (project_id, fingerprint, minute)
TTL minute + INTERVAL 1 YEAR;
