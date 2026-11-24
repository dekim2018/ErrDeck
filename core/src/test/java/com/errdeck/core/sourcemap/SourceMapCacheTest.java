package com.errdeck.core.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceMapCacheTest {

    @Test
    void 같은_소스맵을_여러_스레드가_동시에_요청해도_해석은_한_번이다() throws Exception {
        SourceMapCache<String> cache = new SourceMapCache<>(128L * 1024 * 1024);
        AtomicInteger parses = new AtomicInteger();
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);

        List<SourceMap> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<SourceMap>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await();
                    return cache.get("release-1/index.js", key -> {
                        parses.incrementAndGet();
                        sleep(200);
                        return map(100);
                    });
                });
            }
            List<Future<SourceMap>> futures = new ArrayList<>();
            for (Callable<SourceMap> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            for (Future<SourceMap> future : futures) {
                results.add(future.get());
            }
        }

        assertEquals(1, parses.get());
        assertEquals(1, cache.loads());
        results.forEach(r -> assertSame(results.getFirst(), r));
    }

    @Test
    void 바이트_합계가_상한을_넘으면_오래_안_쓴_것부터_내보낸다() {
        SourceMap one = map(1000);
        long size = one.estimatedBytes();
        SourceMapCache<String> cache = new SourceMapCache<>(size * 2 + size / 2);
        cache.get("a", k -> map(1000));
        cache.get("b", k -> map(1000));
        cache.get("a", k -> map(1000));

        cache.get("c", k -> map(1000));

        assertEquals(2, cache.size());
        assertTrue(cache.bytes() <= size * 2 + size / 2);
        AtomicInteger reloads = new AtomicInteger();
        cache.get("a", k -> { reloads.incrementAndGet(); return map(1000); });
        cache.get("b", k -> { reloads.incrementAndGet(); return map(1000); });
        assertEquals(1, reloads.get(), "가장 오래 안 쓴 b 만 밀려났어야 한다");
        assertTrue(cache.evictions() >= 1);
    }

    @Test
    void 상한보다_큰_소스맵은_넣지_않는다() {
        SourceMapCache<String> cache = new SourceMapCache<>(100);

        SourceMap big = cache.get("big", k -> map(1000));

        assertTrue(big.segments() > 0);
        assertEquals(0, cache.size());
    }

    @Test
    void 없음과_실패는_캐시에_넣지_않아_다음에_다시_시도한다() {
        SourceMapCache<String> cache = new SourceMapCache<>(1024 * 1024);
        AtomicInteger calls = new AtomicInteger();

        assertNull(cache.get("missing", k -> { calls.incrementAndGet(); return null; }));
        assertThrows(IllegalArgumentException.class, () -> cache.get("broken", k -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("형식이 틀렸다");
        }));
        assertNull(cache.get("missing", k -> { calls.incrementAndGet(); return null; }));

        assertEquals(3, calls.get());
        assertEquals(0, cache.size());
    }

    @Test
    void 캐시에서_찾은_횟수를_센다() {
        SourceMapCache<String> cache = new SourceMapCache<>(1024 * 1024);
        cache.get("a", k -> map(10));
        cache.get("a", k -> map(10));
        cache.get("a", k -> map(10));

        assertEquals(2, cache.hits());
        assertEquals(1, cache.loads());
    }

    /** 구간 n 개짜리 소스맵. */
    private static SourceMap map(int segments) {
        StringBuilder mappings = new StringBuilder("AAAA");
        for (int i = 1; i < segments; i++) {
            mappings.append(",CAAC");
        }
        return SourceMap.parse(("{\"version\":3,\"sources\":[\"a.js\"],\"names\":[],\"mappings\":\"" + mappings + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
