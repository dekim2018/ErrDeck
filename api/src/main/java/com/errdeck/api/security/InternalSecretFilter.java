package com.errdeck.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /api/internal/**} 의 공유 비밀 확인(15.2 의 첫째 체인). Alertmanager 가 경보를 전달할 때 쓴다.
 * 비교에 걸리는 시간으로 비밀을 알아내지 못하게 고정 시간 비교를 쓴다.
 */
public class InternalSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-ErrDeck-Internal-Secret";

    private final byte[] secret;

    public InternalSecretFilter(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
