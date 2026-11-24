package com.errdeck.sdk.tracing;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 의 {@code traceparent} 헤더(8.2). {@code 00-{trace_id}-{span_id}-{flags}} 이고, flags 의 마지막 비트가
 * 표본 여부다. 앞 서비스가 정한 표본 여부를 뒤 서비스가 그대로 따른다(8.4).
 */
public record TraceParent(String traceId, String spanId, boolean sampled) {

    public static final String HEADER = "traceparent";

    private static final Pattern FORMAT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);
    private static final HexFormat HEX = HexFormat.of();

    /** 형식이 틀리거나 모두 0 인 값이면 null. 틀린 헤더 때문에 요청을 실패시키지 않는다. */
    public static TraceParent parse(String header) {
        if (header == null) {
            return null;
        }
        var m = FORMAT.matcher(header.strip());
        if (!m.matches() || ZERO_TRACE.equals(m.group(1)) || ZERO_SPAN.equals(m.group(2))) {
            return null;
        }
        return new TraceParent(m.group(1), m.group(2), (Integer.parseInt(m.group(3), 16) & 1) == 1);
    }

    public String format() {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    public static String newTraceId() {
        return random(16);
    }

    public static String newSpanId() {
        return random(8);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(b);
        // 모두 0 인 값은 표준에서 틀린 값이다.
        b[0] |= 1;
        return HEX.formatHex(b);
    }
}
