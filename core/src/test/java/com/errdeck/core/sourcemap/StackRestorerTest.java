package com.errdeck.core.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.StackFrame;
import com.errdeck.core.files.LocalArtifactStore;
import com.errdeck.core.fingerprint.AppFrames;
import com.errdeck.core.fingerprint.Fingerprint;
import com.errdeck.core.fingerprint.Fingerprinter;
import com.errdeck.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 Vite 번들의 위치(정답 자료)를 저장소에 올린 소스맵으로 되돌린다. */
class StackRestorerTest {

    private static final byte[] RAW = resource("sourcemap/bundle.js.map");
    private static final String BUNDLE = "http://localhost:8092/assets/index-a7VI5c4k.js";

    @TempDir
    Path dir;

    private StackRestorer restorer;
    private int line;
    private int column;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        LocalArtifactStore store = new LocalArtifactStore(dir);
        store.put(9002, "web@1", "assets/index-a7VI5c4k.js.map", RAW);
        restorer = new StackRestorer(store, new SourceMapCache<>(128L * 1024 * 1024));
        // 정답 자료에서 src/collab.ts 로 되돌아가는 위치 하나를 고른다.
        Map<String, Object> expected = (Map<String, Object>) Json.parse(resource("sourcemap/expected.json"));
        Map<String, Object> answer = ((List<Map<String, Object>>) expected.get("answers")).stream()
                .filter(a -> "../../src/collab.ts".equals(a.get("source")))
                .findFirst().orElseThrow();
        line = ((Long) answer.get("line")).intValue();
        column = ((Long) answer.get("column")).intValue();
    }

    @Test
    void 번들_프레임을_원본_파일과_줄로_되돌리고_앱_프레임으로_정하고_앞뒤_코드를_붙인다() {
        StackRestorer.Result result = restorer.restore(9002, event("web@1", frame(BUNDLE + "?v=3", line, column + 1)));

        StackFrame restored = result.event().exceptions().getFirst().frames().getFirst();
        assertTrue(result.restored());
        assertEquals("src/collab.ts", restored.filename());
        assertNotEquals("t", restored.function(), "압축된 이름(t)은 남기지 않고 소스맵의 원래 이름이나 빈 값을 쓴다");
        assertTrue(restored.inApp());
        assertEquals(BUNDLE + "?v=3", restored.absPath());
        assertFalse(restored.context().isEmpty());
        assertEquals(Math.max(1, restored.lineno() - 5), restored.contextStart());
    }

    @Test
    void 소스맵이_없거나_릴리스가_없으면_앱_프레임에서_빼고_대체_지문이_된다() {
        Fingerprinter fingerprinter = new Fingerprinter(new AppFrames(List.of()));

        StackRestorer.Result unknownRelease = restorer.restore(9002, event("web@2", frame(BUNDLE, line, column + 1)));
        StackRestorer.Result noRelease = restorer.restore(9002, event(null, frame(BUNDLE, line, column + 1)));

        assertFalse(unknownRelease.restored());
        assertFalse(noRelease.restored());
        assertEquals(false, unknownRelease.event().exceptions().getFirst().frames().getFirst().inApp());
        assertEquals(Fingerprint.Basis.MESSAGE, fingerprinter.fingerprint(unknownRelease.event()).basis());
    }

    @Test
    void 번들_이름이_달라도_같은_원본_위치면_같은_지문이다() {
        LocalArtifactStore store = new LocalArtifactStore(dir);
        store.put(9002, "web@3", "assets/index-NEWHASH.js.map", RAW);
        Fingerprinter fingerprinter = new Fingerprinter(new AppFrames(List.of()));

        Event first = restorer.restore(9002, event("web@1", frame(BUNDLE, line, column + 1))).event();
        Event second = restorer.restore(9002, event("web@3", frame("https://prod.example.com/assets/index-NEWHASH.js", line, column + 1))).event();

        assertEquals(fingerprinter.fingerprint(first), fingerprinter.fingerprint(second));
        assertEquals(Fingerprint.Basis.STACK, fingerprinter.fingerprint(first).basis());
        assertNotEquals(fingerprinter.fingerprint(first),
                fingerprinter.fingerprint(restorer.restore(9002, event("web@9", frame(BUNDLE, line, column + 1))).event()));
    }

    @Test
    void 자바_프레임과_이미_복원한_프레임은_그대로_둔다() {
        Event java = new Event(UUID.randomUUID(), Instant.now(), "error", null, null, "r", null, null, List.of(), 1,
                List.of(new ExceptionInfo("E", "x", List.of(new StackFrame("com.app.A", "f", "A.java", 1, null, true)))), null, Map.of(),
                List.of());

        assertFalse(restorer.restore(1, java).restored());
        assertEquals(java, restorer.restore(1, java).event());
    }

    @Test
    void 번들_주소에서_소스맵_이름을_만든다() {
        assertEquals("assets/index-a1.js.map", StackRestorer.artifactName("https://x.example.com/assets/index-a1.js?v=1#x"));
        assertEquals("app.js.map", StackRestorer.artifactName("http://localhost:5173/app.js"));
        assertEquals("src/collab.ts", StackRestorer.cleanPath("../../src/collab.ts"));
        assertEquals("node_modules/vue/x.js", StackRestorer.cleanPath("./node_modules/vue/x.js"));
    }

    private static Event event(String release, StackFrame frame) {
        return new Event(UUID.randomUUID(), Instant.now(), "error", null, "production", release, null, null, List.of(), 1,
                List.of(new ExceptionInfo("TypeError", "Cannot read properties of undefined (reading 'length')", List.of(frame))),
                null, Map.of(), List.of());
    }

    private static StackFrame frame(String url, int line, int column) {
        return new StackFrame(null, "t", url, line, column, null);
    }

    private static byte[] resource(String name) {
        try (InputStream in = StackRestorerTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
