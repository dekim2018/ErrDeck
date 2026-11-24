package com.errdeck.worker.out.redis;

import com.errdeck.worker.app.SeenEvents;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 처리한 event_id 를 {@code errdeck:seen:{eventId}} 에 1시간 둔다(10.6). 묶음마다 파이프라인 한 번으로 묻고 남긴다. */
public class RedisSeenEvents implements SeenEvents {

    private static final byte[] MARK = {'1'};

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSeenEvents(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Set<UUID> alreadySeen(Collection<UUID> eventIds) {
        List<UUID> ids = List.copyOf(eventIds);
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (UUID id : ids) {
                connection.keyCommands().exists(key(id));
            }
            return null;
        });
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            Object result = results.get(i);
            if (Boolean.TRUE.equals(result) || (result instanceof Long count && count > 0)) {
                seen.add(ids.get(i));
            }
        }
        return seen;
    }

    @Override
    public void markSeen(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        long seconds = ttl.toSeconds();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (UUID id : eventIds) {
                connection.stringCommands().setEx(key(id), seconds, MARK);
            }
            return null;
        });
    }

    private static byte[] key(UUID id) {
        return ("errdeck:seen:" + id).getBytes(StandardCharsets.UTF_8);
    }
}
