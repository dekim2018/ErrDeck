package com.errdeck.api.app;

import com.errdeck.api.app.ApiException.Kind;
import com.errdeck.api.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * 가입, 로그인, 토큰 재발급, 로그아웃(15.2). 팬파라이브, CoPage 와 같은 방식이다.
 *
 * <ul>
 *   <li>액세스 토큰: HS256 JWT, 15분. 검증은 Spring Security 의 리소스 서버가 한다.</li>
 *   <li>리프레시 토큰: 무작위 32바이트, HttpOnly 쿠키. Redis 에는 SHA-256 만 둔다. 쓸 때마다 새것으로 바꾸고, 이미 쓴 토큰이
 *       다시 오면 탈취로 보고 그 사용자의 리프레시 토큰을 모두 지운다. 쓴 직후 짧은 시간 안에 다시 온 것은 동시 재발급으로 본다.</li>
 * </ul>
 */
public class AuthService {

    public record TokenSettings(Duration accessTokenTtl, Duration refreshTokenTtl, String issuer, String audience) {
    }

    /** 로그인이나 재발급의 결과. refreshToken 은 쿠키로만 내보낸다. */
    public record Session(User user, String accessToken, Instant accessTokenExpiresAt, String refreshToken) {
    }

    /** 없는 이메일로 로그인해도 비밀번호 확인에 같은 시간이 걸리게 이 값과 비교한다. 가입 여부를 시간으로 알아내지 못하게 한다. */
    private final String dummyHash;

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final LoginThrottle throttle;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final TokenSettings settings;
    private final boolean signupEnabled;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenStore refreshTokens, LoginThrottle throttle, PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder, TokenSettings settings, boolean signupEnabled, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.throttle = throttle;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.settings = settings;
        this.signupEnabled = signupEnabled;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public User signup(String email, String password) {
        if (!signupEnabled) {
            throw new ApiException(Kind.FORBIDDEN, "가입을 받지 않는다");
        }
        return users.create(normalizeEmail(email), passwordEncoder.encode(password));
    }

    public Session login(String email, String password) {
        String normalized = normalizeEmail(email);
        throttle.pauseIfNeeded(normalized);
        Optional<UserRepository.Credentials> credentials = users.findByEmail(normalized);
        boolean matches = passwordEncoder.matches(password, credentials.map(UserRepository.Credentials::passwordHash).orElse(dummyHash));
        if (credentials.isEmpty() || !matches) {
            throttle.recordFailure(normalized);
            throw new ApiException(Kind.UNAUTHORIZED, "이메일이나 비밀번호가 맞지 않다");
        }
        throttle.reset(normalized);
        String refreshToken = newRefreshToken();
        refreshTokens.save(hash(refreshToken), credentials.get().user().id(), settings.refreshTokenTtl());
        return session(credentials.get().user(), refreshToken);
    }

    public Session refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new ApiException(Kind.UNAUTHORIZED, "리프레시 토큰이 없다");
        }
        String next = newRefreshToken();
        return switch (refreshTokens.rotate(hash(refreshToken), hash(next), settings.refreshTokenTtl())) {
            case RefreshTokenStore.Use.Active active -> {
                User user = users.findById(active.userId()).orElse(null);
                if (user == null) {
                    refreshTokens.delete(hash(next));
                    throw new ApiException(Kind.UNAUTHORIZED, "사용자가 없다");
                }
                yield session(user, next);
            }
            case RefreshTokenStore.Use.RecentlyUsed recent ->
                    throw new ApiException(Kind.RETRY, "다른 요청이 방금 토큰을 재발급했다. 다시 시도한다");
            case RefreshTokenStore.Use.Reused reused -> {
                // 이미 쓴 토큰이 다시 왔다. 훔친 쪽인지 주인인지 알 수 없으므로 이 사용자의 세션을 모두 끊는다.
                refreshTokens.revokeAll(reused.userId());
                throw new ApiException(Kind.UNAUTHORIZED, "이미 쓴 리프레시 토큰이다. 모든 세션을 끊었다");
            }
            case RefreshTokenStore.Use.Unknown unknown -> throw new ApiException(Kind.UNAUTHORIZED, "리프레시 토큰이 없거나 만료되었다");
        };
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isEmpty()) {
            refreshTokens.delete(hash(refreshToken));
        }
    }

    public User user(long userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(Kind.UNAUTHORIZED, "사용자가 없다"));
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Session session(User user, String refreshToken) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(settings.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(user.id()))
                .issuer(settings.issuer())
                .audience(List.of(settings.audience()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Session(user, accessToken, expiresAt, refreshToken);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
