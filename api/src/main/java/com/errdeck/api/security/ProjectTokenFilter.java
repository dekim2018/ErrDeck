package com.errdeck.api.security;

import com.errdeck.api.app.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 릴리스 등록과 소스맵 업로드의 프로젝트 토큰 확인(15.2 의 둘째 체인). 빌드 스크립트가 쓴다.
 * 토큰의 SHA-256 을 DB와 비교하고, 토큰의 프로젝트와 경로의 프로젝트가 같은지 본다. 원문은 저장하지 않는다(10.1).
 */
public class ProjectTokenFilter extends OncePerRequestFilter {

    /** 확인한 프로젝트 ID를 컨트롤러에 넘기는 요청 속성. */
    public static final String PROJECT_ID = ProjectTokenFilter.class.getName() + ".projectId";
    public static final Pattern PATH = Pattern.compile("^/api/projects/(\\d+)/releases(/.*)?$");

    private final JdbcClient jdbc;

    public ProjectTokenFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher path = PATH.matcher(request.getRequestURI());
        String header = request.getHeader("Authorization");
        if (!path.matches() || header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        long pathProjectId = Long.parseLong(path.group(1));
        Optional<Long> tokenProjectId = jdbc.sql("SELECT project_id FROM api_tokens WHERE token_hash = ? AND revoked_at IS NULL")
                .param(AuthService.hash(header.substring("Bearer ".length()).strip()))
                .query(Long.class)
                .optional();
        if (tokenProjectId.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (tokenProjectId.get() != pathProjectId) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        jdbc.sql("UPDATE api_tokens SET last_used_at = now() WHERE token_hash = ?")
                .param(AuthService.hash(header.substring("Bearer ".length()).strip()))
                .update();
        request.setAttribute(PROJECT_ID, pathProjectId);
        chain.doFilter(request, response);
    }

    /** 이 체인이 맡는 요청: 릴리스 경로의 쓰기 요청. 릴리스 조회(GET)는 콘솔 사용자의 JWT 체인이 맡는다. */
    public static boolean matches(HttpServletRequest request) {
        String method = request.getMethod();
        return PATH.matcher(request.getRequestURI()).matches() && !"GET".equals(method) && !"HEAD".equals(method)
                && !"OPTIONS".equals(method);
    }
}
