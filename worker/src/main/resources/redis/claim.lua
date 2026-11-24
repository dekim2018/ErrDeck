-- 다른 worker 가 오래 확인하지 않은 항목을 이어받는다(6.7).
-- 본문이 바이트라 그대로 돌려주려고 필드를 펼쳐 id, project_id, received_at, body 순서로 넷씩 담는다.
-- KEYS[1] 대기열 스트림  ARGV[1] 소비 그룹  ARGV[2] 이 worker 의 이름  ARGV[3] 최소 대기 시간(ms)  ARGV[4] 최대 개수
local claimed = redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], '0-0', 'COUNT', ARGV[4])
local out = {}
for _, entry in ipairs(claimed[2]) do
  local fields = entry[2]
  if fields then
    local map = {}
    for i = 1, #fields, 2 do
      map[fields[i]] = fields[i + 1]
    end
    table.insert(out, entry[1])
    table.insert(out, map['project_id'] or '0')
    table.insert(out, map['received_at'] or '0')
    table.insert(out, map['body'] or '')
  end
end
return out
