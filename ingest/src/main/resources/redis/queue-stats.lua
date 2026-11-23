-- 대기열 길이와 가장 오래된 항목의 ID. 본문은 크므로 꺼내지 않는다.
-- 항목 ID의 앞부분이 적재 시각(ms)이다. worker 는 확인 처리한 항목을 지우므로 첫 항목이 가장 오래 기다린 것이다.
-- KEYS[1] 대기열 스트림
local first = redis.call('XRANGE', KEYS[1], '-', '+', 'COUNT', 1)
local id = ''
if first[1] then
  id = first[1][1]
end
local time = redis.call('TIME')
return {redis.call('XLEN', KEYS[1]), id, tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)}
