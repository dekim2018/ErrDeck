package com.errdeck.api.in.web;

import com.errdeck.api.app.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 실패를 RFC 9457 형식으로 돌려준다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        HttpStatus status = switch (e.kind()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case RETRY -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    /** 커넥션 풀에서 2초 안에 커넥션을 얻지 못했다(14.6). 요청을 쌓지 않고 503 으로 돌려보낸다. */
    @ExceptionHandler(org.springframework.jdbc.CannotGetJdbcConnectionException.class)
    ProblemDetail busy(org.springframework.jdbc.CannotGetJdbcConnectionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "조회가 몰려 잠시 뒤 다시 해야 한다");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("요청 값이 틀렸다");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
