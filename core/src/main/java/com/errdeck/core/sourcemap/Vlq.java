package com.errdeck.core.sourcemap;

/**
 * 소스맵 mappings 의 Base64 VLQ 숫자 읽기(7.3). 한 글자가 6비트이고, 맨 위 비트가 이어짐 표시, 첫 글자의 맨 아래 비트가 부호다.
 */
final class Vlq {

    private static final int[] DIGITS = new int[128];

    static {
        java.util.Arrays.fill(DIGITS, -1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alphabet.length(); i++) {
            DIGITS[alphabet.charAt(i)] = i;
        }
    }

    private Vlq() {
    }

    /** {@code text} 의 {@code position[0]} 부터 숫자 하나를 읽고 위치를 옮긴다. */
    static int decode(String text, int[] position) {
        int result = 0;
        int shift = 0;
        while (true) {
            if (position[0] >= text.length()) {
                throw new IllegalArgumentException("mappings 가 숫자 중간에서 끝났다");
            }
            char c = text.charAt(position[0]++);
            int digit = c < 128 ? DIGITS[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("mappings 에 Base64 가 아닌 글자가 있다: " + c);
            }
            result += (digit & 0x1f) << shift;
            if ((digit & 0x20) == 0) {
                break;
            }
            shift += 5;
            if (shift > 30) {
                throw new IllegalArgumentException("mappings 의 숫자가 int 범위를 넘는다");
            }
        }
        boolean negative = (result & 1) == 1;
        result >>>= 1;
        return negative ? -result : result;
    }
}
