package com.errdeck.demo;

/** 데모 앱의 업무 예외. 전역 처리기가 500 응답으로 바꾼다. */
public class DemoFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DemoFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
