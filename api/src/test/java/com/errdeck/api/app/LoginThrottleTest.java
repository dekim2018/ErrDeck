package com.errdeck.api.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    @Test
    void 실패가_기준을_넘으면_응답을_늦추고_성공하면_잊는다() {
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMillis(300), Duration.ofMinutes(10), Clock.systemUTC());

        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("a@example.com");
        }
        long start = System.nanoTime();
        throttle.pauseIfNeeded("a@example.com");
        Duration paused = Duration.ofNanos(System.nanoTime() - start);
        throttle.reset("a@example.com");

        assertThat(paused).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(throttle.recentFailures("a@example.com")).isZero();
        assertThat(throttle.recentFailures("b@example.com")).isZero();
    }
}
