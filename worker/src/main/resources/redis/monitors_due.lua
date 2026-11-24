-- 실행할 때가 된 모니터를 꺼내고 같은 자리에서 다음 실행 시각으로 바꾼다(9.2). 한 스크립트라 worker 가 여러 대여도
-- 같은 모니터를 두 번 꺼내지 않는다. 돌려주는 것은 모니터 ID 와 예정 시각(ms)의 쌍이다.
-- KEYS[1] 일정(정렬 집합)  KEYS[2] 모니터별 주기(초, 해시)  ARGV[1] 지금(ms)  ARGV[2] 최대 개수
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'WITHSCORES', 'LIMIT', 0, tonumber(ARGV[2]))
local out = {}
for i = 1, #due, 2 do
  local id = due[i]
  local interval = tonumber(redis.call('HGET', KEYS[2], id) or '60')
  redis.call('ZADD', KEYS[1], tonumber(ARGV[1]) + interval * 1000, id)
  table.insert(out, id)
  table.insert(out, due[i + 1])
end
return out
