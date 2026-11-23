-- 표본 요청의 트랜잭션(8장). 스팬은 별도 테이블에 한 행씩 두지 않고 같은 행의 배열 열에 넣는다(10.4).
-- 배열은 같은 순서로 스팬 하나씩을 가리킨다. 스팬의 모양은 18, 19 작업에서 계측을 만들며 다시 맞춘다.
CREATE TABLE IF NOT EXISTS transactions (
  project_id         UInt32,
  trace_id           FixedString(32),
  span_id            FixedString(16),
  parent_span_id     FixedString(16),
  name               LowCardinality(String),
  timestamp          DateTime64(3),
  received_at        DateTime64(3),
  duration_ms        UInt32,
  status             LowCardinality(String),
  environment        LowCardinality(String),
  release            LowCardinality(String),
  span_ids           Array(FixedString(16)),
  span_parent_ids    Array(FixedString(16)),
  span_ops           Array(LowCardinality(String)),
  span_descriptions  Array(String),
  span_starts_ms     Array(UInt32),
  span_durations_ms  Array(UInt32),
  -- 연속으로 반복되어 접힌 횟수(8.5). 10 이상이면 화면에 반복 쿼리로 표시한다.
  span_repeats       Array(UInt32),
  -- 한 요청의 흐름을 trace_id 로 찾으므로 건너뛰기 색인을 둔다(10.4).
  INDEX trace_id_idx trace_id TYPE bloom_filter GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toDate(timestamp)
ORDER BY (project_id, name, timestamp)
TTL toDate(timestamp) + INTERVAL 7 DAY
SETTINGS ttl_only_drop_parts = 1;
