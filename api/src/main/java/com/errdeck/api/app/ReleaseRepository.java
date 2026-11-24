package com.errdeck.api.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 릴리스, 릴리스 파일, 프로젝트 토큰(PostgreSQL). */
public interface ReleaseRepository {

    record Release(long id, String version, Instant createdAt) {
    }

    record ReleaseSummary(String version, Instant createdAt, int artifacts, long newIssues, long regressed) {
    }

    record Token(long id, String name, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
    }

    /** 없으면 만들고 있으면 그대로 돌려준다. 같은 빌드를 다시 올려도 순서(created_at)가 바뀌지 않는다. */
    Release upsertRelease(long projectId, String version);

    Optional<Release> findRelease(long projectId, String version);

    void upsertArtifact(long releaseId, String name, long size, String sha256, String path);

    List<ReleaseSummary> releases(long projectId, int limit);

    long createToken(long projectId, String name, String tokenHash);

    List<Token> tokens(long projectId);

    boolean revokeToken(long projectId, long tokenId);
}
