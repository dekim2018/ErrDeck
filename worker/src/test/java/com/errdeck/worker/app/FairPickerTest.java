package com.errdeck.worker.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;

class FairPickerTest {

    private final BlockingQueue<QueueEntry> events = new ArrayBlockingQueue<>(100);
    private final BlockingQueue<QueueEntry> transactions = new ArrayBlockingQueue<>(100);
    private final FairPicker picker = new FairPicker(events, transactions, 3);

    @Test
    void 둘_다_쌓여_있으면_에러_3건마다_트랜잭션_1건을_고른다() throws Exception {
        fill(events, QueueStream.EVENTS, 20);
        fill(transactions, QueueStream.TX, 20);

        assertThat(pick(12)).isEqualTo("EEETEEETEEET");
    }

    @Test
    void 트랜잭션이_없으면_에러만_계속_고른다() throws Exception {
        fill(events, QueueStream.EVENTS, 6);

        assertThat(pick(6)).isEqualTo("EEEEEE");
    }

    @Test
    void 에러가_없으면_트랜잭션을_계속_고른다() throws Exception {
        fill(transactions, QueueStream.TX, 5);

        assertThat(pick(5)).isEqualTo("TTTTT");
    }

    @Test
    void 둘_다_비어_있으면_잠깐_기다린_뒤_null_이다() throws Exception {
        assertThat(picker.next(10)).isNull();
    }

    private String pick(int count) throws InterruptedException {
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < count; i++) {
            order.append(picker.next(10).stream() == QueueStream.EVENTS ? 'E' : 'T');
        }
        return order.toString();
    }

    private static void fill(BlockingQueue<QueueEntry> queue, QueueStream stream, int count) {
        List<QueueEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new QueueEntry(stream, stream + "-" + i, 1, Instant.now(), new byte[0]));
        }
        queue.addAll(entries);
    }
}
