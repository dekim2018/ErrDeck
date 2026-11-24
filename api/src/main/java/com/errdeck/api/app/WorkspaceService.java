package com.errdeck.api.app;

import com.errdeck.api.app.ApiException.Kind;
import com.errdeck.api.app.WorkspaceRepository.Key;
import com.errdeck.api.app.WorkspaceRepository.Member;
import com.errdeck.api.app.WorkspaceRepository.Project;
import com.errdeck.api.app.WorkspaceRepository.SystemView;
import com.errdeck.api.domain.Platform;
import com.errdeck.api.domain.Role;
import com.errdeck.api.domain.User;
import com.errdeck.core.dsn.Dsn;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시스템, 구성원, 프로젝트, DSN 키, 이슈 상태를 바꾸는 일. 권한은 {@link AccessPolicy} 로 먼저 확인한다(15.4).
 */
@Service
public class WorkspaceService {

    private static final Set<String> SETTABLE_STATUSES = Set.of("unresolved", "resolved", "ignored");

    private final WorkspaceRepository workspace;
    private final AccessPolicy access;
    private final URI ingestUrl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceService(WorkspaceRepository workspace, AccessPolicy access, com.errdeck.api.config.ConsoleProperties console,
                            Clock clock) {
        this.workspace = workspace;
        this.access = access;
        this.ingestUrl = console.ingestUrl();
        this.clock = clock;
    }

    /** 관리자만 시스템을 만든다. 만든 사람이 OWNER 가 된다. */
    @Transactional
    public long createSystem(User user, String name) {
        if (!user.admin()) {
            throw new ApiException(Kind.FORBIDDEN, "관리자만 시스템을 만들 수 있다");
        }
        return workspace.createSystem(name.strip(), user.id());
    }

    public List<SystemView> systems(long userId) {
        return workspace.systemsOf(userId);
    }

    public List<Member> members(long userId, long systemId) {
        access.requireSystem(userId, systemId, Role.VIEWER);
        return workspace.members(systemId);
    }

    @Transactional
    public void putMember(long userId, long systemId, String email, Role role) {
        access.requireSystem(userId, systemId, Role.OWNER);
        long target = workspace.userIdByEmail(AuthService.normalizeEmail(email))
                .orElseThrow(() -> ApiException.notFound("사용자"));
        if (target == userId && role != Role.OWNER) {
            ensureAnotherOwner(systemId, userId);
        }
        workspace.putMember(systemId, target, role);
    }

    @Transactional
    public void removeMember(long userId, long systemId, long targetUserId) {
        access.requireSystem(userId, systemId, Role.OWNER);
        ensureAnotherOwner(systemId, targetUserId);
        workspace.removeMember(systemId, targetUserId);
    }

    /** 시스템에 OWNER 가 하나도 남지 않게 하지 않는다. 남으면 아무도 구성원과 키를 고칠 수 없다. */
    private void ensureAnotherOwner(long systemId, long leavingUserId) {
        long otherOwners = workspace.members(systemId).stream()
                .filter(m -> m.role() == Role.OWNER && m.userId() != leavingUserId)
                .count();
        boolean leavingIsOwner = workspace.members(systemId).stream()
                .anyMatch(m -> m.userId() == leavingUserId && m.role() == Role.OWNER);
        if (leavingIsOwner && otherOwners == 0) {
            throw new ApiException(Kind.CONFLICT, "마지막 OWNER 는 빼거나 낮출 수 없다");
        }
    }

    @Transactional
    public long createProject(long userId, long systemId, String slug, Platform platform, Integer rateLimitPerMin,
                              List<String> allowedOrigins) {
        access.requireSystem(userId, systemId, Role.OWNER);
        try {
            return workspace.createProject(systemId, slug, platform, rateLimitPerMin == null ? 6000 : rateLimitPerMin,
                    allowedOrigins == null ? List.of() : allowedOrigins);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(Kind.CONFLICT, "이미 있는 프로젝트 이름이다");
        }
    }

    public List<Project> projects(long userId, long systemId) {
        access.requireSystem(userId, systemId, Role.VIEWER);
        return workspace.projects(systemId);
    }

    public Project project(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.VIEWER);
        return workspace.projectDetail(projectId);
    }

    /** 한도와 허용 출처는 OWNER 만 바꾼다(15.4 의 "한도"). */
    public Project updateProject(long userId, long projectId, Integer rateLimitPerMin, List<String> allowedOrigins) {
        access.requireProject(userId, projectId, Role.OWNER);
        workspace.updateProject(projectId, rateLimitPerMin, allowedOrigins);
        return workspace.projectDetail(projectId);
    }

    public record IssuedKey(Key key, String dsn) {
    }

    /** 새 DSN 키. 공개키는 무작위 16바이트의 16진수 32자다. */
    public IssuedKey createKey(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.OWNER);
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String publicKey = HexFormat.of().formatHex(bytes);
        long keyId = workspace.createKey(projectId, publicKey);
        Key key = workspace.keys(projectId).stream().filter(k -> k.id() == keyId).findFirst().orElseThrow();
        return new IssuedKey(key, dsn(projectId, publicKey));
    }

    public List<IssuedKey> keys(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.OWNER);
        return workspace.keys(projectId).stream().map(k -> new IssuedKey(k, dsn(projectId, k.publicKey()))).toList();
    }

    /**
     * 키를 폐기한다. 교체하는 동안 옛 키를 조금 더 받도록 유예 시간을 줄 수 있다(15.5). ingest 는 키를 30초 기억하므로
     * 유예가 0이어도 최대 30초는 더 받는다.
     */
    public void revokeKey(long userId, long projectId, long keyId, Duration grace) {
        access.requireProject(userId, projectId, Role.OWNER);
        if (grace.isNegative()) {
            throw new ApiException(Kind.BAD_REQUEST, "유예 시간은 0 이상이어야 한다");
        }
        if (!workspace.revokeKey(projectId, keyId, clock.instant().plus(grace))) {
            throw ApiException.notFound("키");
        }
    }

    /**
     * 사용자가 바꿀 수 있는 이슈 상태. 재발(regressed)은 worker 가 정하고, 해결에서 재발로 바뀌는 판정은 15 작업에서 한다.
     */
    public void changeIssueStatus(long userId, long issueId, String status, String resolvedInRelease, Instant ignoredUntil,
                                  Long ignoredUntilCount) {
        access.requireIssue(userId, issueId, Role.MEMBER);
        if (!SETTABLE_STATUSES.contains(status)) {
            throw new ApiException(Kind.BAD_REQUEST, "상태는 " + SETTABLE_STATUSES + " 중 하나다");
        }
        boolean ignored = "ignored".equals(status);
        workspace.updateIssueStatus(issueId, status, "resolved".equals(status) ? resolvedInRelease : null,
                ignored ? ignoredUntil : null, ignored ? ignoredUntilCount : null);
    }

    public long comment(long userId, long issueId, String body) {
        access.requireIssue(userId, issueId, Role.MEMBER);
        return workspace.addComment(issueId, userId, body);
    }

    private String dsn(long projectId, String publicKey) {
        int port = ingestUrl.getPort();
        return new Dsn(ingestUrl.getScheme(), publicKey, ingestUrl.getHost(), port, projectId).toString();
    }
}
