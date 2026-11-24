package com.errdeck.worker.uptime;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 업타임 점검(9장).
 *
 * @param allowedHosts 내부 주소지만 점검을 허용할 호스트(9.4). 로컬 개발에서 같은 도커 네트워크의 서비스
 * @param concurrency  동시에 실행하는 점검 수(14.3)
 * @param retryDelay   한 번 실패하거나 성공했을 때 주기를 기다리지 않고 다시 점검하기까지(9.3)
 */
@ConfigurationProperties("errdeck.uptime")
public record UptimeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<String> allowedHosts,
        @DefaultValue("50") int concurrency,
        @DefaultValue("1s") Duration tick,
        @DefaultValue("30s") Duration syncInterval,
        @DefaultValue("10s") Duration retryDelay,
        @DefaultValue("14d") Duration certWarning) {
}
