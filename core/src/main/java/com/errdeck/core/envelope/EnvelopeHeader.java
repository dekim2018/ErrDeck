package com.errdeck.core.envelope;

import java.time.Instant;
import java.util.Objects;

/**
 * 봉투의 첫 줄.
 *
 * @param sdk    보낸 SDK와 버전. 예: {@code spring/0.1.0}
 * @param sentAt SDK가 보낸 시각. worker가 SDK와 서버의 시계 차이를 잴 때 쓴다
 */
public record EnvelopeHeader(String sdk, Instant sentAt) {

    public EnvelopeHeader {
        Objects.requireNonNull(sdk, "sdk");
        Objects.requireNonNull(sentAt, "sentAt");
    }
}
