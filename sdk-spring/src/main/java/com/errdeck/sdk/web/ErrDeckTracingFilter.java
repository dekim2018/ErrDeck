package com.errdeck.sdk.web;

import com.errdeck.sdk.tracing.ActiveTransaction;
import com.errdeck.sdk.tracing.ErrDeckTracer;
import com.errdeck.sdk.tracing.TraceParent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 들어오는 요청 하나를 트랜잭션으로 기록한다(8.3). 이름은 실제 주소가 아니라 처리한 핸들러의 경로 패턴이다.
 *
 * <p>SSE 와 WebSocket 같은 긴 연결은 연결 수립까지만 기록한다. 비동기로 넘어가면 필터가 바로 돌아오므로 그때까지가 수립 시간이다.
 */
public class ErrDeckTracingFilter extends OncePerRequestFilter implements Ordered {

    private final ErrDeckTracer tracer;

    public ErrDeckTracingFilter(ErrDeckTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean stream = isStream(request);
        ActiveTransaction tx = tracer.start(request.getHeader(TraceParent.HEADER), request.getMethod() + " " + request.getRequestURI(),
                stream ? "http.server.stream" : "http.server");
        boolean error = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            error = true;
            throw e;
        } finally {
            int status = error ? 500 : response.getStatus();
            tracer.finish(tx, name(request), Integer.toString(status), error || status >= 500);
        }
    }

    /** 지표 수집과 상태 확인은 추적하지 않는다. 15초마다 오는 요청이 표본을 채운다. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    /** 경로 패턴이 없으면(정적 파일, 404) 메서드만 남긴다. 실제 주소를 이름으로 쓰면 엔드포인트가 수만 개로 보인다(8.3). */
    private static String name(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + " " + (pattern instanceof String p ? p : "(패턴 없음)");
    }

    private static boolean isStream(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream") || "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    /** 에러 필터({@link ErrDeckServletFilter}) 바깥에 둔다. 에러 이벤트에 trace_id 가 들어가야 한다. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
