package com.errdeck.api.in.web;

import com.errdeck.api.app.AuthService;
import com.errdeck.api.config.AuthProperties;
import com.errdeck.api.domain.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입, 로그인, 재발급, 로그아웃(15.2). 리프레시 토큰은 HttpOnly 쿠키로만 주고받는다. 쿠키는 이 경로에만 실려
 * 다른 API 요청에는 따라가지 않는다.
 */
@RestController
public class AuthController {

    static final String REFRESH_COOKIE = "errdeck_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    public record Credentials(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record UserView(long id, String email, boolean admin) {

        static UserView of(User user) {
            return new UserView(user.id(), user.email(), user.admin());
        }
    }

    public record TokenView(String accessToken, Instant expiresAt, UserView user) {
    }

    private final AuthService auth;
    private final AuthProperties properties;

    public AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView signup(@Valid @RequestBody Credentials body) {
        return UserView.of(auth.signup(body.email(), body.password()));
    }

    @PostMapping("/api/auth/login")
    public TokenView login(@Valid @RequestBody Credentials body, HttpServletResponse response) {
        return session(auth.login(body.email(), body.password()), response);
    }

    @PostMapping("/api/auth/refresh")
    public TokenView refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        return session(auth.refresh(refreshToken), response);
    }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        auth.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private TokenView session(AuthService.Session session, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), properties.refreshTokenTtl()).toString());
        return new TokenView(session.accessToken(), session.accessTokenExpiresAt(), UserView.of(session.user()));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
