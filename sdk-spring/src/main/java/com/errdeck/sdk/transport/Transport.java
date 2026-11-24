package com.errdeck.sdk.transport;

/** 압축된 봉투 하나를 ingest 로 보낸다. */
public interface Transport {

    /**
     * @return ingest 의 응답
     * @throws java.io.IOException 연결하지 못했거나 제한 시간 안에 응답이 없을 때
     */
    Response send(byte[] envelope) throws java.io.IOException, InterruptedException;

    /** @param retryAfterSeconds Retry-After 헤더. 없으면 null */
    record Response(int status, Long retryAfterSeconds) {
    }
}
