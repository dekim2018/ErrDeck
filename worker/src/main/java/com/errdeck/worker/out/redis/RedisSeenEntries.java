package com.errdeck.worker.out.redis;

import com.errdeck.worker.app.SeenEntries;
import com.errdeck.worker.domain.EntryRef;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 처리한 대기열 항목을 {@code errdeck:seen:{대기열}:{항목 ID}} 에 남긴다(10.6). 묶음마다 파이프라인 한 번으로 묻고 남긴다.
 * 보관 시간은 다시 가져가기(claim-min-idle 60초, 30초 주기)와 그 뒤의 재시도를 덮을 만큼이면 된다.
 */
public class RedisSeenEntries implements SeenEntries {

    private static final byte[] MARK = {'1'};

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSeenEntries(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Set<EntryRef> alreadySeen(Collection<EntryRef> entries) {
        List<EntryRef> refs = List.copyOf(entries);
        if (refs.isEmpty()) {
            return Set.of();
        }
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (EntryRef ref : refs) {
                connection.keyCommands().exists(key(ref));
            }
            return null;
        });
        Set<EntryRef> seen = new HashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            Object result = results.get(i);
            if (Boolean.TRUE.equals(result) || (result instanceof Long count && count > 0)) {
                seen.add(refs.get(i));
            }
        }
        return seen;
    }

    @Override
    public void markSeen(Collection<EntryRef> entries) {
        if (entries.isEmpty()) {
            return;
        }
        long seconds = ttl.toSeconds();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (EntryRef ref : entries) {
                connection.stringCommands().setEx(key(ref), seconds, MARK);
            }
            return null;
        });
    }

    static byte[] key(EntryRef ref) {
        return ("errdeck:seen:" + ref.stream().name().toLowerCase(Locale.ROOT) + ":" + ref.id()).getBytes(StandardCharsets.UTF_8);
    }
}
