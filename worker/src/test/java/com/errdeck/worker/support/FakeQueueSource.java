package com.errdeck.worker.support;

import com.errdeck.worker.app.QueueSource;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 메모리 안의 대기열. 읽은 항목 수와 확인 처리한 항목을 기록한다. */
public class FakeQueueSource implements QueueSource {

    private final Map<QueueStream, ConcurrentLinkedDeque<QueueEntry>> pending = new EnumMap<>(QueueStream.class);
    private final Map<QueueStream, ConcurrentLinkedDeque<QueueEntry>> claimable = new EnumMap<>(QueueStream.class);
    public final Set<String> acked = ConcurrentHashMap.newKeySet();
    public final AtomicLong read = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();

    public FakeQueueSource() {
        for (QueueStream stream : QueueStream.values()) {
            pending.put(stream, new ConcurrentLinkedDeque<>());
            claimable.put(stream, new ConcurrentLinkedDeque<>());
        }
    }

    public QueueEntry add(QueueStream stream, Supplier<byte[]> body) {
        QueueEntry entry = new QueueEntry(stream, sequence.incrementAndGet() + "-0", 7, Instant.now(), body.get());
        pending.get(stream).add(entry);
        return entry;
    }

    /** 다른 worker 가 남긴 미확인 항목처럼 이어받기로만 나오게 한다. */
    public void addClaimable(QueueEntry entry) {
        claimable.get(entry.stream()).add(entry);
    }

    @Override
    public List<QueueEntry> read(QueueStream stream, int count, Duration block) {
        List<QueueEntry> out = new ArrayList<>();
        QueueEntry entry;
        while (out.size() < count && (entry = pending.get(stream).poll()) != null) {
            out.add(entry);
        }
        read.addAndGet(out.size());
        if (out.isEmpty()) {
            try {
                Thread.sleep(Math.min(block.toMillis(), 20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return out;
    }

    @Override
    public List<QueueEntry> claim(QueueStream stream, Duration minIdle, int count) {
        return new ArrayList<>(claimable.get(stream));
    }

    @Override
    public void acknowledge(QueueStream stream, Collection<String> ids) {
        acked.addAll(ids);
    }
}
