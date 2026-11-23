package com.errdeck.worker.app;

import com.errdeck.worker.domain.EntryRef;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/** 대기열. 소비 그룹으로 읽고, 쓰기에 성공한 항목만 확인 처리한다(6.7). */
public interface QueueSource {

    /** 새 항목을 최대 {@code count} 개 읽는다. 없으면 {@code block} 만큼 기다린다. */
    List<QueueEntry> read(QueueStream stream, int count, Duration block);

    /** 다른 worker 가 {@code minIdle} 넘게 확인하지 않은 항목을 이어받는다. */
    List<QueueEntry> claim(QueueStream stream, Duration minIdle, int count);

    /** 확인 처리하고 지운다. 지워야 ingest 가 XLEN 으로 보는 대기열 길이가 준다(5장 끝). */
    void acknowledge(QueueStream stream, Collection<String> ids);

    default void acknowledgeAll(Collection<EntryRef> refs) {
        for (QueueStream stream : QueueStream.values()) {
            List<String> ids = refs.stream().filter(r -> r.stream() == stream).map(EntryRef::id).toList();
            if (!ids.isEmpty()) {
                acknowledge(stream, ids);
            }
        }
    }
}
