package com.errdeck.api.in.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertmanager 의 웹훅을 받는 곳(13.6, 15.1). 공유 비밀 체인 뒤에 있다. 받은 경보를 알림으로 보내는 일은 21 작업에서 한다.
 */
@RestController
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    @PostMapping("/api/internal/alerts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void alerts(@RequestBody Map<String, Object> payload) {
        log.info("Alertmanager 경보를 받았다: status={}, alerts={}", payload.get("status"),
                payload.get("alerts") instanceof java.util.List<?> list ? list.size() : 0);
    }
}
