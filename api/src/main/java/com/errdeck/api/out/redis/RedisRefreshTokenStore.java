package com.errdeck.api.out.redis;

import com.errdeck.api.app.RefreshTokenStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 의 리프레시 토큰(10.6 의 {@code errdeck:refresh:{tokenId}}, 14일). 토큰 ID는 원문의 SHA-256 이다.
 *
 * <ul>
 *   <li>{@code errdeck:refresh:{해시}}: {@code active:{사용자}}, 쓴 뒤에는 {@code used:{사용자}:{쓴 시각 ms}}</li>
 *   <li>{@code errdeck:user-refresh:{사용자}}: 그 사용자의 아직 쓰지 않은 토큰 해시. 모든 세션을 끊을 때 쓴다</li>
 * </ul>
 *
 * <p>판정, 표시, 새 토큰 저장을 Lua 스크립트 하나로 해서 같은 토큰으로 동시에 재발급해도 한 번만 처음으로 판정된다.
 * 쓴 시각은 Redis 의 시계로 적어 api 가 여러 대여도 판정이 같다.
 */
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String TOKEN = "errdeck:refresh:";
    private static final String USER = "errdeck:user-refresh:";

    // KEYS[1] 토큰 키, KEYS[2] 새 토큰 키. ARGV[1] 유예(ms), ARGV[2] 사용자 집합 키 앞부분, ARGV[3] 토큰 해시, ARGV[4] 새 해시, ARGV[5] 유효(ms)
    // 돌려주는 값: '' 없음, 'active:{uid}', 'recent:{uid}', 'used:{uid}'
    private static final RedisScript<String> ROTATE = RedisScript.of("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local value = redis.call('GET', KEYS[1])
            if not value then
              return ''
            end
            if string.sub(value, 1, 7) == 'active:' then
              local uid = string.sub(value, 8)
              redis.call('SET', KEYS[1], 'used:' .. uid .. ':' .. now, 'KEEPTTL')
              redis.call('SREM', ARGV[2] .. uid, ARGV[3])
              redis.call('SET', KEYS[2], value, 'PX', ARGV[5])
              redis.call('SADD', ARGV[2] .. uid, ARGV[4])
              redis.call('PEXPIRE', ARGV[2] .. uid, ARGV[5])
              return value
            end
            local uid, usedAt = string.match(value, '^used:([^:]+):(%d+)$')
            if uid and now - tonumber(usedAt) <= tonumber(ARGV[1]) then
              return 'recent:' .. uid
            end
            if uid then
              return 'used:' .. uid
            end
            return ''
            """, String.class);

    private static final RedisScript<Long> DELETE = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if value and string.sub(value, 1, 7) == 'active:' then
              redis.call('SREM', ARGV[1] .. string.sub(value, 8), ARGV[2])
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration reuseGrace;

    public RedisRefreshTokenStore(StringRedisTemplate redis, Duration reuseGrace) {
        this.redis = redis;
        this.reuseGrace = reuseGrace;
    }

    @Override
    public void save(String tokenHash, long userId, Duration ttl) {
        redis.opsForValue().set(TOKEN + tokenHash, "active:" + userId, ttl);
        redis.opsForSet().add(USER + userId, tokenHash);
        redis.expire(USER + userId, ttl);
    }

    @Override
    public Use rotate(String tokenHash, String nextHash, Duration ttl) {
        String result = redis.execute(ROTATE, List.of(TOKEN + tokenHash, TOKEN + nextHash),
                Long.toString(reuseGrace.toMillis()), USER, tokenHash, nextHash, Long.toString(ttl.toMillis()));
        if (result == null || result.isEmpty()) {
            return new Use.Unknown();
        }
        int colon = result.indexOf(':');
        long userId = Long.parseLong(result.substring(colon + 1));
        return switch (result.substring(0, colon)) {
            case "active" -> new Use.Active(userId);
            case "recent" -> new Use.RecentlyUsed(userId);
            case "used" -> new Use.Reused(userId);
            default -> new Use.Unknown();
        };
    }

    @Override
    public void delete(String tokenHash) {
        redis.execute(DELETE, List.of(TOKEN + tokenHash), USER, tokenHash);
    }

    @Override
    public void revokeAll(long userId) {
        Set<String> hashes = redis.opsForSet().members(USER + userId);
        if (hashes != null && !hashes.isEmpty()) {
            redis.delete(hashes.stream().map(h -> TOKEN + h).toList());
        }
        redis.delete(USER + userId);
    }
}
