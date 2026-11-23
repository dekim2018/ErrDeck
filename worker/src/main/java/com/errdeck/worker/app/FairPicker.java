package com.errdeck.worker.app;

import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 계산 스레드 하나가 다음에 처리할 항목을 고른다. 에러 큐를 먼저 비우되, 에러를 {@code eventsPerTransaction} 건
 * 처리할 때마다 트랜잭션을 1건 처리한다(14.5). 트랜잭션이 밀려도 에러는 제때 보이고, 에러가 계속 와도 트랜잭션이 굶지 않는다.
 *
 * <p>스레드마다 하나씩 쓴다. 스레드 안전하지 않다.
 */
final class FairPicker {

    private final BlockingQueue<QueueEntry> events;
    private final BlockingQueue<QueueEntry> transactions;
    private final int eventsPerTransaction;
    private int eventsSinceTransaction;

    FairPicker(BlockingQueue<QueueEntry> events, BlockingQueue<QueueEntry> transactions, int eventsPerTransaction) {
        this.events = events;
        this.transactions = transactions;
        this.eventsPerTransaction = eventsPerTransaction;
    }

    /** 둘 다 비어 있으면 {@code waitMillis} 만큼 에러 큐를 기다린다. 그래도 없으면 null. */
    QueueEntry next(long waitMillis) throws InterruptedException {
        boolean transactionTurn = eventsSinceTransaction >= eventsPerTransaction;
        QueueEntry entry = transactionTurn ? transactions.poll() : events.poll();
        if (entry == null) {
            entry = transactionTurn ? events.poll() : transactions.poll();
        }
        if (entry == null) {
            entry = events.poll(waitMillis, TimeUnit.MILLISECONDS);
        }
        if (entry == null) {
            entry = transactions.poll();
        }
        if (entry != null) {
            eventsSinceTransaction = entry.stream() == QueueStream.TX ? 0 : eventsSinceTransaction + 1;
        }
        return entry;
    }
}
