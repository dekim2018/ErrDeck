package com.errdeck.api.app;

import com.errdeck.api.app.ApiException.Kind;
import com.errdeck.api.domain.ProjectRef;
import com.errdeck.api.domain.Role;
import org.springframework.stereotype.Component;

/**
 * 시스템 단위 권한 검사(15.4). 컨트롤러는 요청마다 여기를 거친다.
 *
 * <p>구성원이 아니면 403 이 아니라 404 를 준다. 다른 시스템에 그런 프로젝트나 이슈가 있는지조차 알려 주지 않기 위해서다.
 * 구성원이지만 역할이 모자라면 403 이다(CHOICES 11-3).
 */
@Component
public class AccessPolicy {

    private final WorkspaceRepository workspace;

    public AccessPolicy(WorkspaceRepository workspace) {
        this.workspace = workspace;
    }

    public Role requireSystem(long userId, long systemId, Role required) {
        Role role = workspace.role(userId, systemId).orElseThrow(() -> ApiException.notFound("시스템"));
        if (!role.atLeast(required)) {
            throw new ApiException(Kind.FORBIDDEN, required + " 이상만 할 수 있다");
        }
        return role;
    }

    public ProjectRef requireProject(long userId, long projectId, Role required) {
        ProjectRef ref = workspace.project(projectId).orElseThrow(() -> ApiException.notFound("프로젝트"));
        guard(userId, ref.systemId(), required, "프로젝트");
        return ref;
    }

    public ProjectRef requireIssue(long userId, long issueId, Role required) {
        ProjectRef ref = workspace.issueProject(issueId).orElseThrow(() -> ApiException.notFound("이슈"));
        guard(userId, ref.systemId(), required, "이슈");
        return ref;
    }

    private void guard(long userId, long systemId, Role required, String what) {
        Role role = workspace.role(userId, systemId).orElseThrow(() -> ApiException.notFound(what));
        if (!role.atLeast(required)) {
            throw new ApiException(Kind.FORBIDDEN, required + " 이상만 할 수 있다");
        }
    }
}
