package com.errdeck.api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ClickHouse 접속과 스키마 실행기 설정.
 *
 * @param url       HTTP 인터페이스 주소
 * @param user      스키마를 만드는 관리 사용자. api 가 조회에 쓰는 사용자({@link #apiUser})와 다르다
 * @param timeout   요청 하나의 제한 시간. api 의 ClickHouse 조회와 같은 10초다(14.9)
 * @param migrate   api 가 뜰 때 스키마를 적용할지
 */
@ConfigurationProperties("errdeck.clickhouse")
public record ClickHouseProperties(
        URI url,
        String database,
        String user,
        String password,
        Duration timeout,
        boolean migrate,
        String workerUser,
        String workerPassword,
        String apiUser,
        String apiPassword) {
}
