package com.errdeck.demo;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 예외를 내는 서비스. 호출 위치가 다른 같은 메서드와 서로 다른 예외 타입을 두어, 지문이 버그마다 갈리는지 화면에서 볼 수 있게 한다.
 */
@Service
public class OrderService {

    public String place(String orderId) {
        // 감시 대상의 흔한 장애: DB 연결이 모자라 모든 요청이 같은 예외를 던진다(5.1).
        throw new DemoFailure("주문 " + orderId + " 을 저장하지 못했다",
                new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3000ms"));
    }

    public int stock(String sku) {
        String[] parts = sku.split("-");
        // 입력 형식이 틀리면 처리하지 않은 배열 범위 예외가 그대로 올라간다.
        return Integer.parseInt(parts[1]);
    }

    @Async
    public void notifyShipping(String orderId) {
        throw new IllegalStateException("배송 알림 " + orderId + " 을 보내지 못했다");
    }
}
