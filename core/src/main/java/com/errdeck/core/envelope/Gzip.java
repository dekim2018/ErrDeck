package com.errdeck.core.envelope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/** 봉투 본문의 gzip 압축과 해제. */
public final class Gzip {

    private Gzip() {
    }

    /** gzip 머리(1f 8b)로 시작하는지. */
    public static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    public static byte[] compress(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * 압축을 푼다. 출력이 {@code maxOutputBytes} 를 넘는 순간 나머지를 읽지 않고 중단한다.
     * 압축률이 매우 높은 본문(작은 입력이 수백 MB로 풀리는 것)으로 메모리를 다 쓰지 않게 하기 위해서다.
     */
    public static byte[] decompress(byte[] compressed, long maxOutputBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, compressed.length * 4));
        byte[] buffer = new byte[8192];
        long total = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed), buffer.length)) {
            int n;
            while ((n = gzip.read(buffer)) != -1) {
                total += n;
                if (total > maxOutputBytes) {
                    throw new EnvelopeTooLargeException("압축을 푼 크기가 상한 " + maxOutputBytes + "바이트를 넘는다");
                }
                out.write(buffer, 0, n);
            }
        } catch (EOFException e) {
            throw new EnvelopeException("압축된 본문이 중간에 끊겼다", e);
        } catch (ZipException e) {
            throw new EnvelopeException("gzip 형식이 아니다: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new EnvelopeException("압축을 풀지 못했다: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }
}
