package com.errdeck.worker.app;

import com.errdeck.worker.domain.IssueKey;
import com.errdeck.worker.domain.IssueSeen;
import com.errdeck.worker.domain.NewIssue;
import com.errdeck.worker.domain.Quarantine;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** PostgreSQL 의 이슈와 격리 기록. */
public interface IssueRepository {

    /** 이미 이슈가 있는 지문의 이슈 ID. 한 번의 쿼리로 묶음 전체를 찾는다(14.5). */
    Map<IssueKey, Long> findIssueIds(Collection<IssueKey> keys);

    /**
     * 이슈를 만들고 지문을 잇는다. 다른 worker 가 같은 지문으로 먼저 만들었으면 그 이슈 ID를 돌려준다.
     * 지문 행의 유일 제약으로 이슈가 하나만 남는다(6.5).
     */
    long createIssue(NewIssue issue);

    /** 이슈마다 최근 발생 시각과 릴리스를 고친다. 이미 더 나중 시각이면 그대로 둔다. */
    void touch(List<IssueSeen> seen);

    void quarantine(List<Quarantine> items);
}
