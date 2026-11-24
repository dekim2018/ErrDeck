package com.errdeck.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 콘솔 로그인 설정(15.2). 기본값은 application.yml 에 있다. */
@ConfigurationProperties("errdeck.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshReuseGrace,
        boolean secureCookie,
        boolean signupEnabled,
        int failuresBeforeDelay,
        Duration failureDelay,
        Duration failureWindow,
        String internalSecret) {
}
