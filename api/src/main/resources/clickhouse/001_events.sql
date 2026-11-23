-- 원본 이벤트(10.2). 이슈별 분당 상한 안에 든 것만 들어온다(10.3).
-- 같은 event_id 는 병합 때 하나로 합친다. worker 가 쓰기 뒤 확인 처리 전에 죽어 다시 쓴 중복이 나중에 사라진다(6.7).
CREATE TABLE IF NOT EXISTS events (
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
