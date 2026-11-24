package com.errdeck.core.sourcemap;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * 해석한 소스맵의 캐시(7.4). 크기는 개수가 아니라 바이트 합계로 제한하고, 오래 안 쓴 것부터 내보낸다.
 *
 * <p>같은 소스맵을 여러 스레드가 동시에 찾으면 한 스레드만 해석하고 나머지는 그 결과를 기다린다. 프론트 배포 직후 새 버그가 터지면
 * 같은 소스맵을 찾는 이벤트가 한꺼번에 오는데, 스레드마다 해석하면 CPU 와 힙이 동시에 바닥난다(adr/0007).
 * core 는 JDK 만 쓰므로 Caffeine 대신 직접 만든다(CHOICES 13-1).
 *
 * @param <K> 캐시 열쇠. 보통 (프로젝트, 릴리스, 파일 이름)
 */
public final class SourceMapCache<K> {

    private final long maxBytes;
    private final Map<K, CompletableFuture<SourceMap>> loading = new ConcurrentHashMap<>();
    /** 쓴 순서대로. 맨 앞이 가장 오래 안 쓴 것이다. 잠금 안에서만 만진다. */
    private final LinkedHashMap<K, SourceMap> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;
    private final LongAdder hits = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    public SourceMapCache(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("캐시 상한은 0보다 커야 한다");
        }
        this.maxBytes = maxBytes;
    }

    /**
     * 캐시에 있으면 돌려주고, 없으면 {@code loader} 로 해석해 넣는다.
     *
     * @param loader 소스맵이 없으면 null 을 돌려준다. null 은 캐시에 넣지 않는다
     * @throws RuntimeException loader 가 던진 것. 실패도 캐시에 넣지 않으므로 다음 요청이 다시 시도한다
     */
    public SourceMap get(K key, Function<K, SourceMap> loader) {
        SourceMap cached = cached(key);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<SourceMap> mine = new CompletableFuture<>();
        CompletableFuture<SourceMap> other = loading.putIfAbsent(key, mine);
        if (other != null) {
            return await(other);
        }
        try {
            // 이긴 뒤에 한 번 더 본다. 앞선 스레드가 막 넣고 자리를 비웠을 수 있다.
            SourceMap again = cached(key);
            if (again != null) {
                mine.complete(again);
                return again;
            }
            SourceMap map = loader.apply(key);
            loads.increment();
            if (map != null) {
                put(key, map);
            }
            mine.complete(map);
            return map;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(key, mine);
        }
    }

    private synchronized SourceMap cached(K key) {
        SourceMap map = entries.get(key);
        if (map != null) {
            hits.increment();
        }
        return map;
    }

    /** 상한보다 큰 소스맵 하나는 넣지 않는다. 넣으면 다른 것을 모두 밀어내고도 상한을 넘는다. */
    private synchronized void put(K key, SourceMap map) {
        long size = map.estimatedBytes();
        if (size > maxBytes) {
            return;
        }
        SourceMap previous = entries.put(key, map);
        if (previous != null) {
            bytes -= previous.estimatedBytes();
        }
        bytes += size;
        Iterator<Map.Entry<K, SourceMap>> eldest = entries.entrySet().iterator();
        while (bytes > maxBytes && eldest.hasNext()) {
            Map.Entry<K, SourceMap> entry = eldest.next();
            if (entry.getKey().equals(key)) {
                continue;
            }
            bytes -= entry.getValue().estimatedBytes();
            eldest.remove();
            evictions.increment();
        }
    }

    private static SourceMap await(CompletableFuture<SourceMap> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("소스맵 해석을 기다리다 중단되었다", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CompletionException(e.getCause());
        }
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 캐시에서 바로 찾은 횟수. errdeck_sourcemap_cache_total{result="hit"} 의 근거다(13.4). */
    public long hits() {
        return hits.sum();
    }

    /** 해석한 횟수. */
    public long loads() {
        return loads.sum();
    }

    public long evictions() {
        return evictions.sum();
    }
}
