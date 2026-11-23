package com.errdeck.core.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 테스트가 함께 쓰는 예제 봉투. SDK는 이 형식대로 쓰고 worker는 이 형식을 읽는다.
 * 한쪽만 형식을 바꾸면 양쪽 테스트 중 하나가 깨진다.
 */
public final class ExampleEnvelopes {

    /** 서버 에러 1건(예외 사슬 2개, 접힌 횟수 7), 브라우저 에러 1건, 트랜잭션 1건, client_report 1건. */
    public static final String EXAMPLE = "envelopes/example.envelope";

    private ExampleEnvelopes() {
    }

    /** 압축하지 않은 봉투 바이트. */
    public static byte[] load(String name) {
        try (InputStream in = ExampleEnvelopes.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("예제 봉투가 없다: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
