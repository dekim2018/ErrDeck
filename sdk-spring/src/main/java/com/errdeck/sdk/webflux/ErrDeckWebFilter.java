package com.errdeck.sdk.webflux;

import com.errdeck.sdk.ErrDeckClient;
import com.errdeck.sdk.RequestContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 앱(팬파라이브 gateway)에서 처리되지 않고 필터 체인을 거슬러 올라온 예외를 보낸다(11.2 끝).
 * 서블릿의 리졸버와 필터가 동작하지 않으므로 따로 둔다. 상태 코드가 500 미만인 예외(잘못된 요청, 찾을 수 없음)는 보내지 않는다.
 *
 * <p>컨트롤러의 {@code @ExceptionHandler} 가 응답으로 바꾼 예외는 이 필터까지 오지 않아 잡지 못한다. gateway 는 컨트롤러가 거의
 * 없고 오류 대부분이 경로 처리와 뒤쪽 서비스 호출에서 나므로 받아들인다.
 */
public class ErrDeckWebFilter implements WebFilter, Ordered {

    private final ErrDeckClient client;

    public ErrDeckWebFilter(ErrDeckClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).doOnError(error -> {
            try {
                if (error instanceof ErrorResponse response && response.getStatusCode().value() < 500) {
                    return;
                }
                client.capture(error, snapshot(exchange));
            } catch (RuntimeException ignored) {
                // 감시 대상의 오류 처리 흐름을 바꾸지 않는다.
            }
        });
    }

    private static RequestContext snapshot(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        String url = request.getURI().getScheme() + "://" + request.getURI().getRawAuthority() + request.getURI().getRawPath();
        // 사용자는 비동기로만 꺼낼 수 있어 리액터 스레드를 막지 않으려고 비워 둔다.
        return new RequestContext(request.getMethod().name(), url, request.getURI().getRawQuery(), headers, null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
