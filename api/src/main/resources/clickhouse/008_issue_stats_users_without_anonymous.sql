-- 사용자 식별자가 없는 이벤트(user_hash = 0)까지 영향 사용자 한 명으로 세던 것을 고친다(12 작업에서 화면으로 발견).
-- 구체화 뷰는 고칠 수 없어 지우고 다시 만든다. 이미 합쳐진 분의 사용자 수는 그대로이고, 다시 만드는 사이에
-- 들어온 행은 집계에 빠질 수 있다. api 가 뜰 때 한 번만 실행되므로 그 사이는 짧다.
DROP VIEW IF EXISTS issue_stats_1m_mv;

CREATE MATERIALIZED VIEW IF NOT EXISTS issue_stats_1m_mv TO issue_stats_1m AS
SELECT
  project_id,
  fingerprint,
  toStartOfMinute(timestamp)               AS minute,
  sum(toUInt64(count))                     AS times,
  uniqStateIf(user_hash, user_hash != 0)   AS users
FROM event_ticks
GROUP BY project_id, fingerprint, minute;
