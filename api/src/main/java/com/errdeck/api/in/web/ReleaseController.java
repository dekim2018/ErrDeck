package com.errdeck.api.in.web;

import com.errdeck.api.app.ReleaseRepository.Release;
import com.errdeck.api.app.ReleaseRepository.ReleaseSummary;
import com.errdeck.api.app.ReleaseRepository.Token;
import com.errdeck.api.app.ReleaseService;
import com.errdeck.api.security.ProjectTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 릴리스와 소스맵(7.2). 등록과 업로드는 둘째 필터 체인(프로젝트 토큰)이 맡고, 목록은 셋째 체인(콘솔 JWT)이 맡는다(15.2).
 */
@RestController
public class ReleaseController {

    public record NewRelease(@NotBlank String version) {
    }

    public record NewToken(@NotBlank @Size(max = 100) String name) {
    }

    private final ReleaseService releases;

    public ReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    @PostMapping("/api/projects/{projectId}/releases")
    public Map<String, Object> register(@PathVariable long projectId, @Valid @RequestBody NewRelease body, HttpServletRequest request) {
        Release release = releases.register(tokenProject(projectId, request), body.version());
        return Map.of("version", release.version(), "createdAt", release.createdAt().toString());
    }

    /** 본문은 소스맵 원문이다. 이름은 번들 파일 이름에 .map 을 붙인 상대 경로다. */
    @PutMapping("/api/projects/{projectId}/releases/{version}/artifacts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@PathVariable long projectId, @PathVariable String version, @RequestParam String name,
                       @RequestBody byte[] content, HttpServletRequest request) {
        releases.upload(tokenProject(projectId, request), version, name, content);
    }

    @GetMapping("/api/projects/{projectId}/releases")
    public List<ReleaseSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return releases.list(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/api/projects/{projectId}/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseService.IssuedToken createToken(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                                  @Valid @RequestBody NewToken body) {
        return releases.createToken(CurrentUser.id(jwt), projectId, body.name());
    }

    @GetMapping("/api/projects/{projectId}/tokens")
    public List<Token> tokens(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return releases.tokens(CurrentUser.id(jwt), projectId);
    }

    @DeleteMapping("/api/projects/{projectId}/tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeToken(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long tokenId) {
        releases.revokeToken(CurrentUser.id(jwt), projectId, tokenId);
    }

    /** 필터가 이미 토큰과 경로의 프로젝트가 같은지 확인했다. 그래도 속성이 없으면 이 체인을 거치지 않은 것이다. */
    private static long tokenProject(long projectId, HttpServletRequest request) {
        Object checked = request.getAttribute(ProjectTokenFilter.PROJECT_ID);
        if (!(checked instanceof Long id) || id != projectId) {
            throw new com.errdeck.api.app.ApiException(com.errdeck.api.app.ApiException.Kind.UNAUTHORIZED, "프로젝트 토큰이 필요하다");
        }
        return projectId;
    }
}
