package com.errdeck.sdk.tracing;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 나가는 HTTP 호출을 스팬으로 남기고 {@code traceparent} 를 붙인다(8.3). 뒤 서비스는 이 스팬을 부모로 자기 트랜잭션을 만든다.
 * RestClient 와 RestTemplate 에 붙는다.
 */
public class TracingClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        ActiveTransaction tx = Tracing.current();
        if (tx == null) {
            return execution.execute(request, body);
        }
        // 주소의 쿼리는 값이 들어 있을 수 있어 뺀다.
        String description = request.getMethod() + " " + request.getURI().getHost() + request.getURI().getPath();
        ActiveTransaction.OpenSpan span = tx.startSpan("http.client", description);
        request.getHeaders().set(TraceParent.HEADER, tx.traceparent(span.id()));
        try {
            return execution.execute(request, body);
        } finally {
            tx.finishSpan(span);
        }
    }
}
