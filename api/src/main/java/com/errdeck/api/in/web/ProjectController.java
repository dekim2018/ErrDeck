package com.errdeck.api.in.web;

import com.errdeck.api.app.WorkspaceRepository.Project;
import com.errdeck.api.app.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 설정과 DSN 키. 한도와 키는 OWNER 만 바꾼다(15.4). */
@RestController
public class ProjectController {

    public record ProjectUpdate(@Min(1) @Max(10_000_000) Integer rateLimitPerMin, List<String> allowedOrigins) {
    }

    private final WorkspaceService workspace;

    public ProjectController(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/api/projects/{projectId}")
    public Project project(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return workspace.project(CurrentUser.id(jwt), projectId);
    }

    @PatchMapping("/api/projects/{projectId}")
    public Project update(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @Valid @RequestBody ProjectUpdate body) {
        return workspace.updateProject(CurrentUser.id(jwt), projectId, body.rateLimitPerMin(), body.allowedOrigins());
    }

    @PostMapping("/api/projects/{projectId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.IssuedKey createKey(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return workspace.createKey(CurrentUser.id(jwt), projectId);
    }

    @GetMapping("/api/projects/{projectId}/keys")
    public List<WorkspaceService.IssuedKey> keys(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return workspace.keys(CurrentUser.id(jwt), projectId);
    }

    /** @param graceMinutes 교체하는 동안 옛 키를 더 받는 시간(15.5). 기본 0 */
    @DeleteMapping("/api/projects/{projectId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long keyId,
                          @RequestParam(defaultValue = "0") long graceMinutes) {
        workspace.revokeKey(CurrentUser.id(jwt), projectId, keyId, Duration.ofMinutes(graceMinutes));
    }
}
