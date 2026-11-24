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

    /**
     * 이 worker 가 받았지만 확인 처리하지 않은 항목을 {@code after} 다음부터 다시 읽는다(XREADGROUP 의 ID 0 읽기).
     * 읽기 응답을 기다리다 제한 시간이 지나 받지 못한 항목을 바로 되찾는다. 다른 worker 의 항목은 건드리지 않는다.
     */
    List<QueueEntry> readOwn(QueueStream stream, int count, String after);

    /**
     * {@code minIdle} 넘게 확인하지 않은 항목을 {@code start} 부터 최대 {@code count} 개 이어받는다. 죽은 worker 의 항목과,
     * 읽기 응답을 기다리다 제한 시간이 지나 이 worker 가 받지 못한 항목이 여기에 든다.
     *
     * @param start 미확인 목록에서 찾기 시작할 위치. 처음은 {@link #CLAIM_START}
     * @return 이어받은 항목과 다음 위치. 다음 위치가 {@link #CLAIM_START} 면 목록을 끝까지 본 것이다
     */
    Claimed claim(QueueStream stream, Duration minIdle, int count, String start);

    String CLAIM_START = "0-0";

    record Claimed(List<QueueEntry> entries, String next) {

        public boolean finished() {
            return CLAIM_START.equals(next);
        }
    }

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
