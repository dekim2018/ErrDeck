package com.errdeck.core.sourcemap;

import com.errdeck.core.json.Json;
import com.errdeck.core.json.JsonException;
import java.util.ArrayList;
import java.util.List;

/**
 * 원본 코드의 앞뒤 몇 줄(7.3). 원본 코드는 크므로 해석한 소스맵에 담지 않고, 필요할 때 소스맵 원문의 sourcesContent 에서
 * 그 파일만 꺼내 자른다. 화면에는 문제 프레임의 앞뒤 5줄만 보인다(15.6).
 */
public final class SourceContext {

    public static final int DEFAULT_RADIUS = 5;

    /**
     * @param firstLine 잘라 낸 첫 줄의 번호(1부터)
     * @param lines     firstLine 부터 차례로
     */
    public record Lines(int firstLine, List<String> lines) {
    }

    private SourceContext() {
    }

    /** @return 원본 코드가 없거나 줄이 범위 밖이면 빈 줄 목록 */
    public static Lines around(byte[] sourceMapJson, int sourceIndex, int line, int radius) {
        Object contents;
        try {
            contents = Json.parseObject(sourceMapJson).get("sourcesContent");
        } catch (JsonException e) {
            throw new IllegalArgumentException("소스맵이 JSON 객체가 아니다", e);
        }
        if (!(contents instanceof List<?> list) || sourceIndex < 0 || sourceIndex >= list.size()
                || !(list.get(sourceIndex) instanceof String content)) {
            return new Lines(line, List.of());
        }
        String[] all = content.split("\r?\n", -1);
        int first = Math.max(1, line - radius);
        int last = Math.min(all.length, line + radius);
        List<String> out = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            out.add(all[i - 1]);
        }
        return new Lines(first, List.copyOf(out));
    }
}
