-- 받은 이벤트 전부의 건수(10.3). Null 엔진이라 저장하지 않고 걸린 구체화 뷰로만 흘려보낸다.
-- 원본을 상한으로 버려도 건수는 여기서 정확하게 남는다.
CREATE TABLE IF NOT EXISTS event_ticks (
  project_id    UInt32,
  fingerprint   FixedString(32),
  timestamp     DateTime64(3),
  count         UInt32,
  user_hash     UInt64
)
ENGINE = Null;
