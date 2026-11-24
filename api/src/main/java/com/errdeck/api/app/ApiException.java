package com.errdeck.api.app;

/**
 * API 가 사용자에게 돌려줄 실패. 종류가 응답 코드가 된다.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 401. 로그인 정보나 토큰이 틀렸다. */
        UNAUTHORIZED,
        /** 403. 구성원이지만 역할이 모자란다. */
        FORBIDDEN,
        /** 404. 없거나, 구성원이 아니라 볼 수 없다. 다른 시스템의 것이 있는지조차 알려 주지 않는다. */
        NOT_FOUND,
        /** 409. 이미 있다. */
        CONFLICT,
        /** 400. 요청 값이 틀렸다. */
        BAD_REQUEST,
        /** 429. 동시 재발급처럼 잠시 뒤 다시 하면 되는 것. */
        RETRY,
        /** 503. 조회가 몰려 자리가 없다. 기다리게 하지 않고 바로 거절한다(14.4). */
        UNAVAILABLE
    }

    private final Kind kind;

    public ApiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static ApiException notFound(String what) {
        return new ApiException(Kind.NOT_FOUND, what + "이(가) 없다");
    }
}
