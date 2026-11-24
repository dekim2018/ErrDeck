package com.errdeck.api.in.web;

import com.errdeck.api.app.AuthService;
import com.errdeck.api.app.WorkspaceRepository.Member;
import com.errdeck.api.app.WorkspaceRepository.Project;
import com.errdeck.api.app.WorkspaceRepository.SystemView;
import com.errdeck.api.app.WorkspaceService;
import com.errdeck.api.domain.Platform;
import com.errdeck.api.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 정보, 시스템, 구성원, 시스템 안의 프로젝트. */
@RestController
public class SystemController {

    public record NewSystem(@NotBlank @Size(max = 100) String name) {
    }

    public record MemberRequest(@NotBlank @Email String email, @NotNull Role role) {
    }

    public record NewProject(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String slug,
            @NotNull Platform platform,
            @Min(1) @Max(10_000_000) Integer rateLimitPerMin,
            List<String> allowedOrigins) {
    }

    public record Me(AuthController.UserView user, List<SystemView> systems) {
    }

    private final WorkspaceService workspace;
    private final AuthService auth;

    public SystemController(WorkspaceService workspace, AuthService auth) {
        this.workspace = workspace;
        this.auth = auth;
    }

    @GetMapping("/api/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        long userId = CurrentUser.id(jwt);
        return new Me(AuthController.UserView.of(auth.user(userId)), workspace.systems(userId));
    }

    @PostMapping("/api/systems")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createSystem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewSystem body) {
        return Map.of("id", workspace.createSystem(auth.user(CurrentUser.id(jwt)), body.name()));
    }

    @GetMapping("/api/systems")
    public List<SystemView> systems(@AuthenticationPrincipal Jwt jwt) {
        return workspace.systems(CurrentUser.id(jwt));
    }

    @GetMapping("/api/systems/{systemId}/members")
    public List<Member> members(@AuthenticationPrincipal Jwt jwt, @PathVariable long systemId) {
        return workspace.members(CurrentUser.id(jwt), systemId);
    }

    @PutMapping("/api/systems/{systemId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long systemId, @Valid @RequestBody MemberRequest body) {
        workspace.putMember(CurrentUser.id(jwt), systemId, body.email(), body.role());
    }

    @DeleteMapping("/api/systems/{systemId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long systemId, @PathVariable long userId) {
        workspace.removeMember(CurrentUser.id(jwt), systemId, userId);
    }

    @PostMapping("/api/systems/{systemId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createProject(@AuthenticationPrincipal Jwt jwt, @PathVariable long systemId,
                                           @Valid @RequestBody NewProject body) {
        return Map.of("id", workspace.createProject(CurrentUser.id(jwt), systemId, body.slug(), body.platform(),
                body.rateLimitPerMin(), body.allowedOrigins()));
    }

    @GetMapping("/api/systems/{systemId}/projects")
    public List<Project> projects(@AuthenticationPrincipal Jwt jwt, @PathVariable long systemId) {
        return workspace.projects(CurrentUser.id(jwt), systemId);
    }
}
