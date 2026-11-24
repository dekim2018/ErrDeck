package com.errdeck.api.config;

import com.errdeck.api.app.AuthService;
import com.errdeck.api.app.LoginThrottle;
import com.errdeck.api.app.UserRepository;
import com.errdeck.api.out.redis.RedisRefreshTokenStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, ConsoleProperties.class})
public class IdentityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** 위임 인코더. 지금은 bcrypt 이고, 방식을 바꿔도 옛 해시를 읽는다(15.2). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthService authService(UserRepository users, StringRedisTemplate redis, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                            AuthProperties properties, Clock clock) {
        return new AuthService(users,
                new RedisRefreshTokenStore(redis, properties.refreshReuseGrace()),
                new LoginThrottle(properties.failuresBeforeDelay(), properties.failureDelay(), properties.failureWindow(), clock),
                passwordEncoder, jwtEncoder,
                new AuthService.TokenSettings(properties.accessTokenTtl(), properties.refreshTokenTtl(), properties.issuer(),
                        properties.audience()),
                properties.signupEnabled(), clock);
    }
}
