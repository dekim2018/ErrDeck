package com.errdeck.ingest.domain;

import java.time.Duration;

/** 대기열에 넣으려 한 결과. */
public sealed interface Admission {

    record Accepted() implements Admission {
    }

    /** 프로젝트 한도를 넘었다. {@code retryAfter} 뒤에 토큰이 하나 생긴다. */
    record RateLimited(Duration retryAfter) implements Admission {
    }

    /** 대기열이 상한에 닿았다. */
    record QueueFull() implements Admission {
    }

    /** Redis 를 쓸 수 없다. 느리거나, 끊겼거나, 진행 중인 호출이 상한에 닿았다. */
    record Unavailable() implements Admission {
    }
}
