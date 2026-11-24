package com.errdeck.api.in.web;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertmanager 의 웹훅을 받는 곳(13.6, 15.1). 공유 비밀 체인 뒤에 있다. 받은 경보는 'resource' 규칙의 알림으로 넘긴다(21 작업).
 */
@RestController
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final MeterRegistry registry;
    private final com.errdeck.api.app.OpsService ops;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public InternalController(MeterRegistry registry, com.errdeck.api.app.OpsService ops,
                              org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.registry = registry;
        this.ops = ops;
        this.redis = redis;
    }

    @PostMapping("/api/internal/alerts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void alerts(@RequestBody Map<String, Object> payload) {
        List<?> alerts = payload.get("alerts") instanceof List<?> list ? list : List.of();
        for (Object alert : alerts) {
            if (alert instanceof Map<?, ?> map && map.get("labels") instanceof Map<?, ?> labels) {
                String status = String.valueOf(map.get("status"));
                String name = String.valueOf(labels.get("alertname"));
                log.warn("경보 {}: {} {}", status, name, labels);
                // 경보 이름은 규칙 파일의 8개뿐이라 태그로 넣어도 시계열이 늘지 않는다(13.4).
                registry.counter("errdeck.alerts.received", "alertname", name, "status", status).increment();
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> maps = (List<Map<String, Object>>) (List<?>) alerts.stream().filter(a -> a instanceof Map<?, ?>).toList();
        try {
            ops.alertmanager(maps, redis);
        } catch (RuntimeException e) {
            log.warn("경보를 알림으로 넘기지 못했다: {}", e.toString());
        }
    }
}
