CREATE MATERIALIZED VIEW IF NOT EXISTS issue_stats_1m_mv TO issue_stats_1m AS
SELECT
  project_id,
  fingerprint,
  toStartOfMinute(timestamp)    AS minute,
  sum(toUInt64(count))          AS times,
  uniqState(user_hash)          AS users
FROM event_ticks
GROUP BY project_id, fingerprint, minute;
