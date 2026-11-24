-- 확인 처리하고 지운다. 지워야 ingest 가 XLEN 으로 보는 대기열 길이가 준다(5장 끝).
-- KEYS[1] 대기열 스트림  ARGV[1] 소비 그룹  ARGV[2..] 항목 ID
local ids = {}
for i = 2, #ARGV do
  ids[#ids + 1] = ARGV[i]
end
redis.call('XACK', KEYS[1], ARGV[1], unpack(ids))
return redis.call('XDEL', KEYS[1], unpack(ids))
