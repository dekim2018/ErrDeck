package com.errdeck.worker.app;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** 이미 처리한 event_id(6.7). SDK가 응답을 못 받아 다시 보낸 이벤트를 거른다. */
public interface SeenEvents {

    /** 주어진 ID 중 이미 처리한 것. */
    Set<UUID> alreadySeen(Collection<UUID> eventIds);

    /** 쓰기에 성공한 뒤에 남긴다. 쓰기 전에 남기면 쓰기에 실패한 이벤트를 다음에 중복으로 착각한다. */
    void markSeen(Collection<UUID> eventIds);
}
