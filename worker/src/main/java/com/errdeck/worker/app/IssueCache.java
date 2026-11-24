package com.errdeck.worker.app;

import com.errdeck.worker.domain.IssueKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지문과 이슈의 대응을 기억한다(6.5). 폭주 때는 거의 모든 이벤트가 같은 지문이므로 적중률이 높다.
 * 오래 쓰지 않은 것부터 버려 크기를 지킨다. 쓰기 스레드 하나만 쓰므로 잠그지 않는다.
 */
final class IssueCache {

    private final Map<IssueKey, Long> entries;

    IssueCache(int capacity) {
        this.entries = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<IssueKey, Long> eldest) {
                return size() > capacity;
            }
        };
    }

    Long get(IssueKey key) {
        return entries.get(key);
    }

    void put(IssueKey key, long issueId) {
        entries.put(key, issueId);
    }

    int size() {
        return entries.size();
    }
}
