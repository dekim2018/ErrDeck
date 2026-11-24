package com.errdeck.api.in.web;

import com.errdeck.api.app.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 이슈 상태 변경과 댓글. MEMBER 이상(15.4). 이슈 목록과 상세 조회는 12 작업에서 더한다. */
@RestController
public class IssueStatusController {

    public record StatusChange(@NotBlank String status, String resolvedInRelease, Instant ignoredUntil) {
    }

    public record NewComment(@NotBlank @Size(max = 10_000) String body) {
    }

    private final WorkspaceService workspace;

    public IssueStatusController(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @PatchMapping("/api/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable long issueId, @Valid @RequestBody StatusChange body) {
        workspace.changeIssueStatus(CurrentUser.id(jwt), issueId, body.status(), body.resolvedInRelease(), body.ignoredUntil());
    }

    @PostMapping("/api/issues/{issueId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> comment(@AuthenticationPrincipal Jwt jwt, @PathVariable long issueId, @Valid @RequestBody NewComment body) {
        return Map.of("id", workspace.comment(CurrentUser.id(jwt), issueId, body.body()));
    }
}
