package com.errdeck.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주기적으로 실패하는 정산 작업. 스케줄러 경로의 예외가 이슈가 되는지 본다. */
@Component
@ConditionalOnBooleanProperty(name = "demo.scheduled-failure.enabled", matchIfMissing = true)
public class ScheduledFailure {

    @Scheduled(fixedDelayString = "${demo.scheduled-failure.interval}", initialDelayString = "${demo.scheduled-failure.interval}")
    public void settle() {
        throw new IllegalStateException("정산 배치가 실패했다");
    }
}
