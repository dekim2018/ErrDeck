package com.errdeck.core.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.errdeck.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 실제 Vite 빌드 결과물(scripts/sourcemap-fixture)의 위치 5,385개를 source-map 라이브러리의 답과 대조한다(adr/0007).
 */
class SourceMapTest {

    private static final byte[] RAW = resource("sourcemap/bundle.js.map");
    private static final SourceMap MAP = SourceMap.parse(RAW);

    @Test
    @SuppressWarnings("unchecked")
    void source_map_라이브러리가_구한_정답_위치가_모두_일치한다() {
        Map<String, Object> expected = (Map<String, Object>) Json.parse(resource("sourcemap/expected.json"));
        List<Map<String, Object>> answers = (List<Map<String, Object>>) expected.get("answers");

        List<String> mismatches = new ArrayList<>();
        int withSource = 0;
        for (Map<String, Object> answer : answers) {
            int line = ((Long) answer.get("line")).intValue();
            int column = ((Long) answer.get("column")).intValue();
            Optional<OriginalPosition> actual = MAP.lookup(line, column);
            String want = answer.get("source") == null ? "없음"
                    : answer.get("source") + ":" + answer.get("originalLine") + ":" + answer.get("originalColumn") + ":" + answer.get("name");
            String got = actual.map(p -> p.source() + ":" + p.line() + ":" + p.column() + ":" + p.name()).orElse("없음");
            if (!want.equals(got)) {
                mismatches.add(line + ":" + column + " 기대 " + want + ", 실제 " + got);
            }
            if (actual.isPresent()) {
                withSource++;
            }
        }

        assertEquals(List.of(), mismatches.subList(0, Math.min(10, mismatches.size())));
        assertEquals(5385, answers.size());
        assertEquals(5376, withSource);
    }

    @Test
    void 내_코드의_위치를_원본_파일과_줄로_되돌리고_앞뒤_코드를_자른다() {
        // 번들 안에서 src/collab.ts 의 change.ops.length 줄로 되돌아가는 위치를 찾는다.
        OriginalPosition found = null;
        SourceContext.Lines context = null;
        for (int line = 1; line <= 20 && found == null; line++) {
            for (int column = 0; column < 70_000 && found == null; column++) {
                Optional<OriginalPosition> p = MAP.lookup(line, column);
                if (p.isPresent() && p.get().source().endsWith("src/collab.ts")) {
                    SourceContext.Lines lines = SourceContext.around(RAW, p.get().sourceIndex(), p.get().line(), SourceContext.DEFAULT_RADIUS);
                    if (lines.lines().get(p.get().line() - lines.firstLine()).contains("change.ops.length")) {
                        found = p.get();
                        context = lines;
                    }
                }
            }
        }

        assertTrue(found != null, "src/collab.ts 의 change.ops.length 위치를 찾지 못했다");
        assertEquals("../../src/collab.ts", found.source());
        assertEquals(found.line() - 5, context.firstLine());
        assertEquals(11, context.lines().size());
    }

    @Test
    void 원본_코드가_없거나_줄이_끝에_가까우면_있는_만큼만_자른다() {
        SourceContext.Lines first = SourceContext.around(RAW, 5, 1, 5);
        SourceContext.Lines none = SourceContext.around(RAW, 99, 1, 5);

        assertEquals(1, first.firstLine());
        assertEquals(6, first.lines().size());
        assertTrue(none.lines().isEmpty());
    }

    @Test
    void 범위_밖의_위치는_비어_있다() {
        assertTrue(MAP.lookup(0, 0).isEmpty());
        assertTrue(MAP.lookup(9_999, 0).isEmpty());
        assertTrue(MAP.lookup(1, -1).isEmpty());
    }

    @Test
    void 직접_만든_소스맵의_차이값을_누적해_읽는다() {
        // 줄 1: 열 0 → a.js 0:0, 열 5 → a.js 0:4 이름 x. 줄 2: 열 2 는 원본 없음, 열 3 → b.js 2:5(원본 열은 앞 구간의 4에 1을 더한다)
        String json = """
                {"version":3,"sources":["a.js","b.js"],"names":["x"],"mappings":"AAAA,KAAIA;E,CCEC"}
                """;
        SourceMap map = SourceMap.parse(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(new OriginalPosition("a.js", 0, 1, 0, null), map.lookup(1, 3).orElseThrow());
        assertEquals(new OriginalPosition("a.js", 0, 1, 4, "x"), map.lookup(1, 99).orElseThrow());
        assertTrue(map.lookup(2, 2).isEmpty());
        assertEquals(new OriginalPosition("b.js", 1, 3, 5, null), map.lookup(2, 3).orElseThrow());
        assertEquals(4, map.segments());
    }

    @Test
    void sourceRoot_를_원본_경로_앞에_붙인다() {
        SourceMap map = SourceMap.parse("""
                {"version":3,"sourceRoot":"webpack:///","sources":["./src/a.ts"],"names":[],"mappings":"AAAA"}
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals("webpack:///./src/a.ts", map.lookup(1, 0).orElseThrow().source());
    }

    @Test
    void 형식이_틀린_소스맵을_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":2,\"sources\":[],\"names\":[],\"mappings\":\"\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":3,\"sections\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"AAAA!\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"ACAA\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"AAAAC\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"AAAAAA\"}"));
    }

    @Test
    void 열_순서가_뒤섞인_줄도_정렬해서_찾는다() {
        SourceMap map = SourceMap.parse("""
                {"version":3,"sources":["a.js"],"names":[],"mappings":"KAAC,LAAC"}
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, map.lookup(1, 0).orElseThrow().column() - 2);
        assertEquals(1, map.lookup(1, 5).orElseThrow().column());
    }

    @Test
    void 힙_크기를_구간_수에_비례해_어림한다() {
        assertTrue(MAP.estimatedBytes() > 20L * MAP.segments());
        assertTrue(MAP.estimatedBytes() < 10L * 1024 * 1024);
    }

    private static void parse(String json) {
        SourceMap.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] resource(String name) {
        try (InputStream in = SourceMapTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
