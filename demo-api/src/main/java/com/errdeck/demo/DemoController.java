package com.errdeck.demo;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 예외 경로마다 하나씩 둔 엔드포인트(10 작업). 500번대는 이슈가 되고 400번대는 되지 않아야 한다.
 *
 * <ul>
 *   <li>{@code /demo/handled/{id}}: 전역 처리기가 500 으로 바꾼 예외</li>
 *   <li>{@code /demo/unhandled?sku=}: 처리하지 않고 올라간 예외</li>
 *   <li>{@code /demo/async/{id}}: 반환값 없는 비동기 메서드의 예외. 응답은 202</li>
 *   <li>{@code /demo/bad-request}: 400 으로 끝나는 예외</li>
 *   <li>{@code /demo/ok}: 예외 없는 요청. SDK 부담 실험의 기준</li>
 * </ul>
 * 스케줄 예외는 {@link ScheduledFailure} 가 주기적으로 낸다.
 */
@RestController
public class DemoController {

    private final OrderService orders;

    public DemoController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/demo/handled/{id}")
    public String handled(@PathVariable String id) {
        return orders.place(id);
    }

    @GetMapping("/demo/unhandled")
    public Map<String, Integer> unhandled(@RequestParam(defaultValue = "broken") String sku) {
        return Map.of("stock", orders.stock(sku));
    }

    @GetMapping("/demo/async/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> async(@PathVariable String id) {
        orders.notifyShipping(id);
        return Map.of("status", "accepted");
    }

    @GetMapping("/demo/bad-request")
    public String badRequest() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수량은 1 이상이어야 한다");
    }

    @GetMapping("/demo/ok")
    public Map<String, String> ok() {
        return Map.of("status", "ok");
    }
}
