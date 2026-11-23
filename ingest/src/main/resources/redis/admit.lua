-- 프로젝트 한도 차감, 대기열 길이 확인, 적재를 한 번에 한다(5.4, adr/0003).
-- 스크립트는 다른 명령과 섞이지 않고 실행되므로, 동시에 온 요청이 같은 토큰을 두 번 쓰지 못한다.
--
-- KEYS[1] 토큰 버킷 (errdeck:rl:{projectId})
-- KEYS[2] 대기열 스트림
-- ARGV[1] 분당 한도  ARGV[2] 버킷 크기  ARGV[3] 버킷 만료(ms)
-- ARGV[4] 대기열 상한  ARGV[5] 프로젝트 ID  ARGV[6] 압축된 봉투
--
-- 돌려주는 값: {202, 0} 적재, {429, 토큰이 생길 때까지 ms}, {503, 0} 대기열이 참

-- 여러 ingest 가 같은 버킷을 쓰므로 각자의 시계가 아니라 Redis 의 시계로 잰다.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])
if tokens == nil or ts == nil then
  tokens = burst
  ts = now
end
tokens = math.min(burst, tokens + math.max(0, now - ts) * rate / 60000)

if tokens < 1 then
  redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
  redis.call('PEXPIRE', KEYS[1], ARGV[3])
  return {429, math.ceil((1 - tokens) * 60000 / rate)}
end

-- 대기열이 찼으면 토큰을 쓰지 않는다. 프로젝트 잘못이 아니기 때문이다.
if redis.call('XLEN', KEYS[2]) >= tonumber(ARGV[4]) then
  return {503, 0}
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens - 1), 'ts', now)
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('XADD', KEYS[2], '*', 'project_id', ARGV[5], 'received_at', now, 'body', ARGV[6])
return {202, 0}
