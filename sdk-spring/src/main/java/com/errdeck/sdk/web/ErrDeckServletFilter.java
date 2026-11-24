package com.errdeck.sdk.web;

import com.errdeck.sdk.ErrDeckClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 응답이 끝난 뒤 최종 상태 코드가 500 이상일 때만, 리졸버가 적어 둔 예외를 보낸다(11.2). 잘못된 입력 같은 400번대
 * 예외는 이슈가 되지 않는다. 처리되지 않고 필터까지 올라온 예외는 그대로 보내고 다시 던진다.
 */
public class ErrDeckServletFilter extends OncePerRequestFilter implements Ordered {

    private final ErrDeckClient client;

    public ErrDeckServletFilter(ErrDeckClient client) {
        this.client = client;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            client.capture(unwrap(e), snapshot(request));
            throw e;
        }
        try {
            if (response.getStatus() >= 500 && request.getAttribute(ErrDeckExceptionResolver.ATTRIBUTE) instanceof Throwable t) {
                client.capture(t, snapshot(request));
            }
        } catch (RuntimeException ignored) {
            // 응답은 이미 끝났다. SDK 문제로 감시 대상의 요청을 실패시키지 않는다.
        }
    }

    private static Throwable unwrap(Throwable e) {
        return e instanceof ServletException se && se.getRootCause() != null ? se.getRootCause() : e;
    }

    private static com.errdeck.sdk.RequestContext snapshot(HttpServletRequest request) {
        try {
            return ServletRequests.snapshot(request);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
