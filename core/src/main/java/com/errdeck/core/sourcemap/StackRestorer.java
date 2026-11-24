package com.errdeck.core.sourcemap;

import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.StackFrame;
import com.errdeck.core.files.ArtifactStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * 브라우저 스택을 소스맵으로 원본 파일과 줄로 되돌린다(7장). worker 의 계산 단계가 지문을 만들기 전에 부른다. 복원 결과가 지문의
 * 재료이기 때문이다(6.3).
 *
 * <ul>
 *   <li>번들 주소에서 출처와 쿼리를 떼고 {@code .map} 을 붙인 이름으로 (프로젝트, 릴리스, 이름)의 소스맵을 찾는다.</li>
 *   <li>찾으면 원본 경로, 함수 이름, 줄과 열로 바꾸고 {@code node_modules} 밖이면 앱 프레임으로 정한다.</li>
 *   <li>못 찾은 번들 프레임은 앱 프레임에서 뺀다. 번들 이름은 빌드마다 바뀌어 지문에 쓰면 안 되므로, 앱 프레임이 하나도 남지
 *       않으면 예외 타입과 값을 지운 메시지로 지문을 만든다(7.5).</li>
 *   <li>맨 위 앱 프레임 하나에만 원본 코드의 앞뒤 5줄을 붙인다.</li>
 * </ul>
 *
 * <p>소스맵은 바이트 상한 캐시에 둔다. 처음 한 번만 저장소에서 읽어 해석한다(7.4). 스레드 안전하다.
 */
public final class StackRestorer {

    private static final Pattern URL_ORIGIN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*");
    private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#].*$");
    private static final Pattern LEADING_RELATIVE = Pattern.compile("^(?:\\.\\.?/)+");
    private static final int CONTEXT_CACHE = 10_000;

    /** 소스맵 캐시의 열쇠. */
    public record ArtifactKey(long projectId, String release, String name) {
    }

    /** @param restored 번들 프레임을 하나라도 되돌렸는지 */
    public record Result(Event event, boolean restored) {
    }

    private final ArtifactStore store;
    private final SourceMapCache<ArtifactKey> cache;
    private final Map<String, SourceContext.Lines> contexts = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SourceContext.Lines> eldest) {
            return size() > CONTEXT_CACHE;
        }
    };
    private final LongAdder missing = new LongAdder();

    public StackRestorer(ArtifactStore store, SourceMapCache<ArtifactKey> cache) {
        this.store = store;
        this.cache = cache;
    }

    public Result restore(long projectId, Event event) {
        if (!hasBundleFrames(event)) {
            return new Result(event, false);
        }
        boolean restoredAny = false;
        boolean contextAttached = false;
        List<ExceptionInfo> chain = new ArrayList<>();
        for (ExceptionInfo exception : event.exceptions()) {
            List<StackFrame> frames = new ArrayList<>(exception.frames().size());
            for (StackFrame frame : exception.frames()) {
                if (!isBundleFrame(frame)) {
                    frames.add(frame);
                    continue;
                }
                Optional<StackFrame> restored = restoreFrame(projectId, event.release(), frame, !contextAttached);
                if (restored.isPresent()) {
                    restoredAny = true;
                    contextAttached |= !restored.get().context().isEmpty();
                    frames.add(restored.get());
                } else {
                    frames.add(new StackFrame(null, frame.function(), frame.filename(), frame.lineno(), frame.colno(), false));
                }
            }
            chain.add(new ExceptionInfo(exception.type(), exception.value(), frames));
        }
        Event restored = new Event(event.eventId(), event.timestamp(), event.level(), event.message(), event.environment(), event.release(),
                event.userId(), event.traceId(), event.fingerprint(), event.count(), chain, event.request(), event.tags(), event.breadcrumbs());
        return new Result(restored, restoredAny);
    }

    private Optional<StackFrame> restoreFrame(long projectId, String release, StackFrame frame, boolean wantContext) {
        if (release == null || release.isEmpty()) {
            return Optional.empty();
        }
        ArtifactKey key = new ArtifactKey(projectId, release, artifactName(frame.filename()));
        SourceMap map = cache.get(key, k -> store.get(k.projectId(), k.release(), k.name()).map(SourceMap::parse).orElse(null));
        if (map == null) {
            missing.increment();
            return Optional.empty();
        }
        // 브라우저 스택의 열은 1부터, 소스맵 조회는 0부터 센다(CHOICES 13-2).
        Optional<OriginalPosition> position = map.lookup(frame.lineno(), frame.colno() - 1);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        OriginalPosition p = position.get();
        String path = cleanPath(p.source());
        boolean inApp = !path.contains("node_modules/");
        SourceContext.Lines lines = wantContext && inApp ? context(key, p) : null;
        // 소스맵에 원래 이름이 없으면 함수 이름을 비운다. 브라우저 스택의 이름은 압축된 이름(Oo, t)이라 빌드마다 바뀌어
        // 지문을 흔든다. 이때 지문은 원본 경로만으로 만든다(CHOICES 15-4).
        return Optional.of(new StackFrame(null, p.name(), path, p.line(), p.column() + 1, inApp,
                frame.filename(), lines == null ? List.of() : lines.lines(), lines == null ? null : lines.firstLine()));
    }

    private SourceContext.Lines context(ArtifactKey key, OriginalPosition p) {
        String cacheKey = key.projectId() + "|" + key.release() + "|" + key.name() + "|" + p.sourceIndex() + "|" + p.line();
        synchronized (contexts) {
            SourceContext.Lines cached = contexts.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        SourceContext.Lines lines = store.get(key.projectId(), key.release(), key.name())
                .map(raw -> SourceContext.around(raw, p.sourceIndex(), p.line(), SourceContext.DEFAULT_RADIUS))
                .orElse(null);
        if (lines != null) {
            synchronized (contexts) {
                contexts.put(cacheKey, lines);
            }
        }
        return lines;
    }

    /** 번들 주소 {@code https://x/assets/index-a1.js?v=1} 의 소스맵 이름은 {@code assets/index-a1.js.map} 이다. */
    public static String artifactName(String bundleUrl) {
        String path = URL_ORIGIN.matcher(bundleUrl).replaceAll("");
        path = QUERY_OR_FRAGMENT.matcher(path).replaceAll("");
        return path.replaceAll("^/+", "") + ".map";
    }

    /** 소스맵의 {@code ../../src/collab.ts} 를 화면과 지문에 쓸 {@code src/collab.ts} 로. */
    static String cleanPath(String source) {
        String path = URL_ORIGIN.matcher(source).replaceAll("");
        return LEADING_RELATIVE.matcher(path.replaceAll("^/+", "")).replaceAll("");
    }

    /** 소스맵이 없어 되돌리지 못한 프레임 수. errdeck_sourcemap_cache_total{result="missing"} 의 근거다(13.4). */
    public long missing() {
        return missing.sum();
    }

    private static boolean hasBundleFrames(Event event) {
        return event.exceptions().stream().flatMap(e -> e.frames().stream()).anyMatch(StackRestorer::isBundleFrame);
    }

    /** 자바 프레임(module 있음)과 이미 복원한 프레임(absPath 있음)은 건드리지 않는다. */
    private static boolean isBundleFrame(StackFrame frame) {
        return frame.module() == null && frame.absPath() == null && frame.filename() != null && frame.lineno() != null
                && frame.colno() != null && frame.filename().matches("^[A-Za-z][A-Za-z0-9+.-]*://.*");
    }
}
