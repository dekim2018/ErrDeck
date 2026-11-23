package com.errdeck.ingest.domain;

import java.time.Instant;
import java.util.Set;

/**
 * DSN 키 하나로 알 수 있는 것. 캐시에 30초 머물므로 폐기 여부는 쓸 때 시각과 비교한다.
 *
 * @param revokedAt 폐기 시각. 교체 기간을 두려고 미래 시각일 수 있다(15.5)
 */
public record ProjectKey(long projectId, Platform platform, int rateLimitPerMin, Set<String> allowedOrigins, Instant revokedAt) {

    public ProjectKey {
        allowedOrigins = Set.copyOf(allowedOrigins);
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null || now.isBefore(revokedAt);
    }

    /** 브라우저 프로젝트의 키는 누구나 볼 수 있으므로 출처를 확인한다. 서버 프로젝트는 헤더를 꾸밀 수 있어 의미가 없다. */
    public boolean requiresOriginCheck() {
        return platform == Platform.VUE;
    }

    public boolean allowsOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }
}
