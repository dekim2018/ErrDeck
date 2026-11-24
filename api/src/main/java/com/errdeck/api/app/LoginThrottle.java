package com.errdeck.api.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 같은 이메일로 로그인 실패가 반복되면 응답을 늦춘다(15.2). 비밀번호를 빠르게 대입하지 못하게 한다.
 * 잠그지 않고 늦추기만 해서 다른 사람이 일부러 실패시켜 주인을 막을 수 없다.
 *
 * <p>api 프로세스 안에서만 센다. api 를 여러 대 띄우면 대마다 센다(CHOICES 11-5).
 */
public final class LoginThrottle {

    /** 기억하는 이메일 수의 상한. 넘으면 오래된 것부터 잊는다. 대입 공격이 이 표로 메모리를 늘리지 못하게 한다. */
    private static final int MAX_TRACKED = 10_000;

    private final int failuresBeforeDelay;
    private final Duration delay;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(int failuresBeforeDelay, Duration delay, Duration window, Clock clock) {
        this.failuresBeforeDelay = failuresBeforeDelay;
        this.delay = delay;
        this.window = window;
        this.clock = clock;
    }

    /** 늦춰야 하면 기다린다. 가상 스레드라 기다리는 동안 실행 스레드를 붙잡지 않는다. */
    public void pauseIfNeeded(String email) {
        if (recentFailures(email) >= failuresBeforeDelay) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void recordFailure(String email) {
        if (failures.size() >= MAX_TRACKED && !failures.containsKey(email)) {
            failures.clear();
        }
        Deque<Instant> times = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(clock.instant());
            while (times.size() > failuresBeforeDelay * 2) {
                times.removeFirst();
            }
        }
    }

    public void reset(String email) {
        failures.remove(email);
    }

    int recentFailures(String email) {
        Deque<Instant> times = failures.get(email);
        if (times == null) {
            return 0;
        }
        Instant since = clock.instant().minus(window);
        synchronized (times) {
            times.removeIf(t -> t.isBefore(since));
            return times.size();
        }
    }
}
