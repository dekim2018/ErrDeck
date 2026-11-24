package com.errdeck.sdk.webflux;

import com.errdeck.sdk.tracing.ActiveTransaction;
import com.errdeck.sdk.tracing.ErrDeckTracer;
import com.errdeck.sdk.tracing.TraceParent;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 앱(팬파라이브 gateway)의 요청을 트랜잭션으로 기록한다. 안쪽 스팬은 남기지 않는다. 뒤로 넘기는 요청의
 * {@code traceparent} 를 이 트랜잭션의 것으로 바꿔, 뒤 서비스가 gateway 를 부모로 이어 붙게 한다(8.2).
 */
public class ErrDeckTracingWebFilter implements WebFilter, Ordered {

    private final ErrDeckTracer tracer;

    public ErrDeckTracingWebFilter(ErrDeckTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        ActiveTransaction tx = tracer.startDetached(request.getHeaders().getFirst(TraceParent.HEADER),
                request.getMethod() + " " + request.getPath().value(), "http.server");
        if (tx == null) {
            return chain.filter(exchange);
        }
        ServerWebExchange traced = exchange.mutate()
                .request(r -> r.headers(h -> h.set(TraceParent.HEADER, tx.traceparent(tx.spanId()))))
                .build();
        return chain.filter(traced)
                .doOnError(e -> tracer.finish(tx, name(traced), "500", true))
                .doOnSuccess(v -> {
                    var status = traced.getResponse().getStatusCode();
                    int code = status == null ? 200 : status.value();
                    tracer.finish(tx, name(traced), Integer.toString(code), code >= 500);
                });
    }

    private static String name(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern != null ? pattern.toString() : "(패턴 없음)";
        return exchange.getRequest().getMethod() + " " + path;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
