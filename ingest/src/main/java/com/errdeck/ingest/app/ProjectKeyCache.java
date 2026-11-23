package com.errdeck.ingest.app;

import com.errdeck.ingest.config.IngestProperties;
import com.errdeck.ingest.domain.ProjectKey;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * DSN 키 조회 결과를 30초 기억한다. 없는 키도 기억해, 잘못된 키로 오는 요청이 매번 DB를 치지 않게 한다(5.4).
 * 같은 키를 동시에 처음 조회하면 DB 조회는 한 번만 한다.
 */
@Component
public class ProjectKeyCache {

    /** DSN 의 공개키 형식과 같다. 형식이 틀린 값은 DB와 캐시에 닿지 않게 한다. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final LoadingCache<String, Optional<ProjectKey>> cache;
    private final Clock clock;

    public ProjectKeyCache(ProjectKeyRepository repository, IngestProperties properties, Clock clock) {
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.keyCache().ttl())
                .maximumSize(properties.keyCache().maxSize())
                .build(repository::findByPublicKey);
    }

    /** 지금 쓸 수 있는 키만 돌려준다. 저장소를 쓸 수 없으면 예외를 던진다. */
    public Optional<ProjectKey> findActive(String publicKey) {
        if (publicKey == null || !KEY.matcher(publicKey).matches()) {
            return Optional.empty();
        }
        return cache.get(publicKey).filter(key -> key.isActiveAt(clock.instant()));
    }
}
