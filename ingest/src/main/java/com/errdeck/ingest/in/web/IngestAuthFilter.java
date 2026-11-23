package com.errdeck.ingest.in.web;

import com.errdeck.ingest.app.IngestMetrics;
import com.errdeck.ingest.app.IngestMetrics.Result;
import com.errdeck.ingest.app.ProjectKeyCache;
import com.errdeck.ingest.config.IngestProperties;
import com.errdeck.ingest.domain.ProjectKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ingest 의 인증. 크기, 키, 출처를 본문을 읽기 전에 확인한다(5.4의 1~3단계).
 *
 * <p>Spring Security 를 쓰지 않는다(15.3). DSN 키는 비밀이 아니라 식별자라서 사용자, 역할, 권한이 없고,
 * 요청마다 쓰지 않는 필터 십여 개를 지나갈 이유가 없다.
 */
@Component
public class IngestAuthFilter extends OncePerRequestFilter {

    static final String PROJECT_KEY = IngestAuthFilter.class.getName() + ".projectKey";
    static final String KEY_HEADER = "X-ErrDeck-Key";

    private static final Logger log = LoggerFactory.getLogger(IngestAuthFilter.class);
    private static final Pattern PATH = Pattern.compile("/ingest/([1-9][0-9]{0,9})/envelope");

    private final ProjectKeyCache keys;
    private final IngestProperties properties;
    private final IngestMetrics metrics;

    public IngestAuthFilter(ProjectKeyCache keys, IngestProperties properties, IngestMetrics metrics) {
        this.keys = keys;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/ingest/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher path = PATH.matcher(request.getRequestURI());
        if (!path.matches() || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > properties.maxEnvelopeSize().toBytes()) {
            metrics.request(Result.TOO_LARGE);
            Responses.reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, null);
            return;
        }

        String publicKey = request.getHeader(KEY_HEADER);
        if (publicKey == null) {
            publicKey = QueryString.get(request, "key");
        }
        Optional<ProjectKey> found;
        try {
            found = keys.findActive(publicKey);
        } catch (RuntimeException e) {
            log.warn("DSN 키를 조회하지 못했다: {}", e.toString());
            metrics.request(Result.UNAVAILABLE);
            Responses.reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, properties.queue().fullRetryAfter());
            return;
        }
        long projectId = Long.parseLong(path.group(1));
        if (found.isEmpty() || found.get().projectId() != projectId) {
            metrics.request(Result.UNAUTHORIZED);
            Responses.reject(response, HttpServletResponse.SC_UNAUTHORIZED, null);
            return;
        }

        ProjectKey key = found.get();
        if (key.requiresOriginCheck()) {
            String origin = request.getHeader("Origin");
            if (!key.allowsOrigin(origin)) {
                metrics.request(Result.FORBIDDEN);
                Responses.reject(response, HttpServletResponse.SC_FORBIDDEN, null);
                return;
            }
            // 단순 요청이라 사전 요청은 없지만, SDK 가 응답 코드와 Retry-After 를 읽으려면 이 헤더가 있어야 한다.
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Expose-Headers", "Retry-After");
            response.setHeader("Vary", "Origin");
        }
        request.setAttribute(PROJECT_KEY, key);
        chain.doFilter(request, response);
    }
}
