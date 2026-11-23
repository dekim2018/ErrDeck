-- 업타임 점검 결과(9장).
CREATE TABLE IF NOT EXISTS uptime_checks (
  monitor_id       UInt64,
  project_id       UInt32,
  timestamp        DateTime64(3),
  ok               UInt8,
  status_code      UInt16,
  duration_ms      UInt32,
  error            String,
  cert_expires_at  Nullable(DateTime)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (monitor_id, timestamp)
TTL toDate(timestamp) + INTERVAL 90 DAY;
