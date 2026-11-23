package com.errdeck.core.envelope;

/**
 * 봉투 크기 상한. 값은 5.3과 5.4를 따른다. 각 프로세스는 설정 속성으로 이 값을 바꿀 수 있다.
 *
 * @param maxCompressedBytes   압축된 봉투 하나의 상한. ingest가 본문을 읽기 전에 확인한다
 * @param maxDecompressedBytes 압축을 푼 결과의 상한. 작게 압축된 거대한 본문을 worker가 끝까지 풀지 않게 한다
 */
public record EnvelopeLimits(long maxCompressedBytes, long maxDecompressedBytes) {

    public static final EnvelopeLimits DEFAULT = new EnvelopeLimits(200 * 1024, 2 * 1024 * 1024);

    public EnvelopeLimits {
        if (maxCompressedBytes <= 0 || maxDecompressedBytes <= 0) {
            throw new IllegalArgumentException("상한은 0보다 커야 한다");
        }
    }
}
