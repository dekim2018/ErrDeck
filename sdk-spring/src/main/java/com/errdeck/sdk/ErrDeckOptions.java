package com.errdeck.sdk;

import com.errdeck.core.dsn.Dsn;
import java.time.Duration;
import java.util.List;

/**
 * SDK 가 동작하는 데 필요한 값. 스프링 설정 속성({@link com.errdeck.sdk.autoconfigure.ErrDeckProperties})에서 만든다.
 * 값은 11.1, 11.2, 14.9 를 따른다.
 *
 * @param queueCapacity 요청 스레드가 넣는 전송 큐의 길이. 차면 기다리지 않고 버리고 센다
 * @param batchSize     전송 스레드가 한 번에 보내는 최대 건수
 * @param flushInterval 건수가 덜 모여도 보내는 간격
 * @param timeout       ingest 호출 하나의 제한 시간
 * @param shutdownFlush 종료할 때 큐를 비우는 데 쓰는 최대 시간
 * @param foldWindow    같은 에러를 세는 구간
 * @param foldThreshold 구간 안에서 이만큼은 그대로 보내고, 넘는 것은 횟수만 센다
 */
public record ErrDeckOptions(
        Dsn dsn,
        String environment,
        String release,
        List<String> inAppPackages,
        int queueCapacity,
        int batchSize,
        Duration flushInterval,
        Duration timeout,
        Duration shutdownFlush,
        Duration foldWindow,
        int foldThreshold) {

    public ErrDeckOptions {
        inAppPackages = inAppPackages == null ? List.of() : List.copyOf(inAppPackages);
    }

    public static ErrDeckOptions defaults(Dsn dsn) {
        return new ErrDeckOptions(dsn, null, null, List.of(), 1000, 50, Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(2), Duration.ofSeconds(10), 5);
    }
}
