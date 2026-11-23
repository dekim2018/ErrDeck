package com.errdeck.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * ingest 의 상한과 한도. 기본값은 application.yml 에 있다.
 *
 * @param maxEnvelopeSize 압축된 봉투 하나의 상한. 넘으면 413(5.3)
 */
@ConfigurationProperties("errdeck.ingest")
public record IngestProperties(DataSize maxEnvelopeSize, KeyCache keyCache, RateLimit rateLimit, Queue queue, Redis redis) {

    /** 키 조회 결과를 기억하는 시간과 개수. 없는 키도 기억한다(5.4). */
    public record KeyCache(Duration ttl, long maxSize) {
    }

    /**
     * 프로젝트 한도의 토큰 버킷. 분당 한도는 프로젝트마다 DB에 있고, 순간 허용량은 모든 프로젝트에 같다.
     *
     * @param burst     버킷의 크기. 한꺼번에 받을 수 있는 봉투 수
     * @param bucketTtl 버킷 키의 만료. 오래 조용한 프로젝트의 키를 Redis 에 남기지 않는다
     */
    public record RateLimit(long burst, Duration bucketTtl) {
    }

    /**
     * @param fullRetryAfter  대기열이 찼거나 Redis 를 쓸 수 없을 때 SDK 에게 쉬라고 알려 줄 시간
     * @param metricsInterval 대기열 길이와 나이 지표를 갱신하는 주기
     */
    public record Queue(long eventsMaxLength, long txMaxLength, Duration fullRetryAfter, Duration metricsInterval) {
    }

    /** @param maxInFlight 동시에 진행 중인 Redis 호출의 상한. 가상 스레드에는 천장이 없으므로 직접 둔다(14.4) */
    public record Redis(int maxInFlight) {
    }
}
