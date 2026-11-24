package com.errdeck.api.app;

import com.errdeck.api.domain.Platform;
import com.errdeck.api.domain.ProjectRef;
import com.errdeck.api.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 시스템, 구성원, 프로젝트, DSN 키, 이슈 상태(PostgreSQL). */
public interface WorkspaceRepository {

    record SystemView(long id, String name, Role role) {
    }

    record Member(long userId, String email, Role role) {
    }

    record Project(long id, long systemId, String slug, Platform platform, int rateLimitPerMin, List<String> allowedOrigins) {
    }

    record Key(long id, long projectId, String publicKey, Instant createdAt, Instant revokedAt) {
    }

    Optional<Role> role(long userId, long systemId);

    Optional<ProjectRef> project(long projectId);

    Optional<ProjectRef> issueProject(long issueId);

    /** 시스템을 만들고 만든 사람을 OWNER 로 넣는다. */
    long createSystem(String name, long ownerId);

    List<SystemView> systemsOf(long userId);

    List<Member> members(long systemId);

    void putMember(long systemId, long userId, Role role);

    void removeMember(long systemId, long userId);

    long createProject(long systemId, String slug, Platform platform, int rateLimitPerMin, List<String> allowedOrigins);

    List<Project> projects(long systemId);

    Project projectDetail(long projectId);

    void updateProject(long projectId, Integer rateLimitPerMin, List<String> allowedOrigins);

    /** 사용자가 볼 수 있는 프로젝트 전부. ClickHouse 쿼리에 강제로 넣는 조건이다(15.4). */
    Set<Long> visibleProjectIds(long userId);

    long createKey(long projectId, String publicKey);

    List<Key> keys(long projectId);

    /** @return 바꾼 행이 있으면 true */
    boolean revokeKey(long projectId, long keyId, Instant revokedAt);

    /** @return 바꾼 행이 있으면 true */
    boolean updateIssueStatus(long issueId, String status, String resolvedInRelease, Instant ignoredUntil);

    long addComment(long issueId, long userId, String body);

    Optional<Long> userIdByEmail(String email);
}
