package com.errdeck.worker.support;

import com.errdeck.worker.app.BatchWriter;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.Quarantine;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 쓴 묶음을 기록한다. 실패나 멈춤을 흉내 낼 수 있다. */
public class FakeBatchWriter implements BatchWriter {

    public final List<List<EventRow>> batches = new CopyOnWriteArrayList<>();
    public final List<Quarantine> quarantined = new CopyOnWriteArrayList<>();
    public final AtomicInteger failuresLeft = new AtomicInteger();
    public volatile boolean down;

    @Override
    public BatchWrite prepare(List<ProcessedEntry> batch) {
        return () -> {
            if (down) {
                throw new IllegalStateException("저장소가 멈춰 있다");
            }
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("일시적인 실패");
            }
            batches.add(batch.stream().flatMap(e -> e.rows().stream()).toList());
            batch.forEach(e -> quarantined.addAll(e.quarantined()));
        };
    }

    public int rows() {
        return batches.stream().mapToInt(List::size).sum();
    }
}
