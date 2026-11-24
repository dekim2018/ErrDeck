package com.errdeck.sdk.autoconfigure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 감시 대상의 설정(11.2).
 *
 * <pre>
 * errdeck:
 *   dsn: https://{공개키}@{호스트}/{프로젝트ID}
 *   environment: production
 *   release: ${GIT_COMMIT:unknown}
 *   in-app-packages: com.livebroadcast
 * </pre>
 *
 * @param dsn           비어 있으면 SDK 가 켜지지 않는다
 * @param enabled       false 면 SDK 를 끈다. 테스트나 로컬에서 끌 때 쓴다
 * @param inAppPackages 내 코드의 패키지. 지문은 이 패키지의 프레임으로 만든다(6.3)
 */
@ConfigurationProperties("errdeck")
public record ErrDeckProperties(
        String dsn,
        Boolean enabled,
        String environment,
        String release,
        List<String> inAppPackages,
        Integer queueCapacity,
        Integer batchSize,
        Duration flushInterval,
        Duration timeout,
        Duration shutdownFlush,
        Duration foldWindow,
        Integer foldThreshold) {
}
