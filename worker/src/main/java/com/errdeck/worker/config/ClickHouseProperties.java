package com.errdeck.worker.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param timeout 묶음 쓰기 하나의 제한 시간. 넘으면 묶음을 다시 쓰고 확인 처리하지 않는다(14.9) */
@ConfigurationProperties("errdeck.clickhouse")
public record ClickHouseProperties(URI url, String database, String user, String password, Duration timeout) {
}
