package com.errdeck.demo;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 팬파라이브와 CoPage처럼 업무 예외를 응답으로 바꾸는 전역 처리기. SDK 는 최종 상태가 500 이상일 때만 보낸다(11.2). */
@RestControllerAdvice
public class DemoExceptionHandler {

    @ExceptionHandler(DemoFailure.class)
    public ResponseEntity<Map<String, String>> failure(DemoFailure e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "주문을 처리하지 못했다"));
    }
}
