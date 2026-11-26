package com.errdeck.worker.app;

import com.errdeck.worker.domain.EntryRef;
import java.util.Collection;
import java.util.Set;

/**
 * 이미 처리한 대기열 항목(6.7). worker 가 쓰기 뒤 확인 처리 전에 죽어 다른 worker 가 같은 항목을 다시 가져간 경우를 거른다.
 *
 * <p>event_id 가 아니라 항목(봉투) 단위로 기억한다. 이벤트마다 남기면 초당 5,000건의 폭주에서 Redis 데이터 상한을 몇 분 만에
 * 채웠다(29 작업). SDK 는 실패한 봉투를 다시 보내지 않으므로 같은 이벤트가 다른 항목으로 다시 들어오는 경로는 없다.
 */
public interface SeenEntries {

    /** 주어진 항목 중 이미 처리한 것. */
    Set<EntryRef> alreadySeen(Collection<EntryRef> entries);

    /** 쓰기에 성공한 뒤에 남긴다. 쓰기 전에 남기면 쓰기에 실패한 항목을 다음에 중복으로 착각한다. */
    void markSeen(Collection<EntryRef> entries);
}
