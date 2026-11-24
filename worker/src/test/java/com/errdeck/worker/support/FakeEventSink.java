package com.errdeck.worker.support;

import com.errdeck.worker.app.EventSink;
import com.errdeck.worker.domain.EventRow;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 쓴 묶음을 기록한다. 실패나 멈춤을 흉내 낼 수 있다. */
public class FakeEventSink implements EventSink {

    public final List<List<EventRow>> batches = new CopyOnWriteArrayList<>();
    public final AtomicInteger failuresLeft = new AtomicInteger();
    public volatile boolean down;

    @Override
    public void write(List<EventRow> rows) {
        if (down) {
            throw new IllegalStateException("ClickHouse 가 멈춰 있다");
        }
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("일시적인 실패");
        }
        batches.add(List.copyOf(rows));
    }

    public int rows() {
        return batches.stream().mapToInt(List::size).sum();
    }
}
