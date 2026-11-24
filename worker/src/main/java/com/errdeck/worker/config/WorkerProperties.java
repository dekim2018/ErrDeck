package com.errdeck.worker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * worker 의 단계 구조(14.5)와 묶음 쓰기(10.4)의 값. 기본값은 application.yml 에 있다.
 *
 * @param handoffCapacity      스트림별 넘겨주기 큐의 길이. 차면 읽기가 멈춘다
 * @param computeThreads       계산 풀의 크기. CPU 상한과 같게 둔다(14.2)
 * @param eventsPerTransaction 계산 풀이 트랜잭션 1건을 처리하기 전에 처리하는 에러 수. 트랜잭션이 밀려도 에러가 제때 보이게 한다
 * @param writeQueueCapacity   계산을 마친 대기열 항목을 담는 큐의 길이. 차면 계산이 멈춘다
 * @param claimMinIdle         다른 worker 가 이 시간 넘게 확인하지 않은 항목을 이어받는다(6.7)
 * @param retryMaxBackoff      ClickHouse 쓰기에 실패한 묶음을 다시 쓸 때 기다리는 시간의 상한
 * @param inAppPackages        SDK 가 in_app 을 정하지 않은 자바 프레임을 앱 프레임으로 볼 패키지
 */
@ConfigurationProperties("errdeck.worker")
public record WorkerProperties(
        String consumerGroup,
        int readCount,
        Duration readBlock,
        int handoffCapacity,
        int computeThreads,
        int eventsPerTransaction,
        int writeQueueCapacity,
        int batchMaxRows,
        Duration batchMaxWait,
        Duration claimInterval,
        Duration claimMinIdle,
        Duration retryMaxBackoff,
        Duration shutdownTimeout,
        List<String> inAppPackages) {
}
