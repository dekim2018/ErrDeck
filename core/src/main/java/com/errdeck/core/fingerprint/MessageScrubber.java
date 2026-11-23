package com.errdeck.core.fingerprint;

import java.util.regex.Pattern;

/**
 * 메시지에서 요청마다 달라지는 값을 자리 표시로 바꾼다(6.3). {@code User 1024 not found} 는 {@code User <num> not found} 가 된다.
 * 값이 섞인 메시지로 지문을 만들면 사용자마다 다른 이슈가 생긴다(6.2).
 *
 * <p>순서가 중요하다. 따옴표 안을 먼저 지워야 그 안의 숫자가 따로 바뀌지 않고, UUID 를 숫자보다 먼저 지워야 조각나지 않는다.
 */
public final class MessageScrubber {

    /** 앞 글자가 영문자면 {@code can't} 같은 축약이므로 따옴표로 보지 않는다. */
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|(?<![A-Za-z])'[^']*'");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** {@code 0x7f3a} 형식, 또는 숫자와 a-f 가 섞인 8자 이상(해시, 주소). 영단어 {@code deadbeef} 같은 순수 문자는 그대로 둔다. */
    private static final Pattern HEX = Pattern.compile(
            "\\b0[xX][0-9a-fA-F]+\\b|\\b(?=[0-9a-fA-F]*[a-fA-F])(?=[0-9a-fA-F]*[0-9])[0-9a-fA-F]{8,}\\b");
    /** 식별자 안의 숫자({@code utf8}, {@code IPv4})는 값이 아니므로 앞이 영문자나 밑줄이면 그대로 둔다. */
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_0-9])[0-9]+(?:\\.[0-9]+)*");

    private MessageScrubber() {
    }

    public static String scrub(String message) {
        if (message == null) {
            return "";
        }
        String scrubbed = QUOTED.matcher(message).replaceAll("<str>");
        scrubbed = UUID.matcher(scrubbed).replaceAll("<uuid>");
        scrubbed = HEX.matcher(scrubbed).replaceAll("<hex>");
        return NUMBER.matcher(scrubbed).replaceAll("<num>");
    }
}
