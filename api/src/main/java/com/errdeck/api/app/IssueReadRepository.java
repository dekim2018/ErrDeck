package com.errdeck.api.app;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 이슈 조회(PostgreSQL). */
public interface IssueReadRepository {

    record IssueRow(long id, long projectId, String status, String title, String culprit, Instant firstSeen, Instant lastSeen,
                    String lastRelease, String resolvedInRelease, Instant ignoredUntil, List<String> fingerprints) {
    }

    record Comment(long id, String author, String body, Instant createdAt) {
    }

    /** 최근 발생 순 한 쪽. */
    List<IssueRow> recent(long projectId, String status, String release, int limit, int offset);

    /** 지문으로 이슈를 찾는다. 상태와 릴리스 조건을 함께 건다. */
    List<IssueRow> byFingerprints(long projectId, Collection<String> fingerprints, String status, String release);

    Optional<IssueRow> find(long issueId);

    List<Comment> comments(long issueId);

    /** 지문과 이슈의 대응. 이슈 목록을 횟수순으로 묶을 때 쓴다. */
    Map<String, Long> issueIdsByFingerprint(long projectId, Collection<String> fingerprints);

    String projectSlug(long projectId);
}
