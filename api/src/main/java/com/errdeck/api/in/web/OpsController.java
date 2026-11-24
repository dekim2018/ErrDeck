package com.errdeck.api.in.web;

import com.errdeck.api.app.OpsService;
import com.errdeck.api.app.OverviewService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 업타임 모니터(9장), 알림 규칙(16장), 통합 화면(12장). */
@RestController
public class OpsController {

    private final OpsService ops;
    private final OverviewService overview;

    public OpsController(OpsService ops, OverviewService overview) {
        this.ops = ops;
        this.overview = overview;
    }

    @GetMapping("/api/overview")
    public OverviewService.Overview overview(@AuthenticationPrincipal Jwt jwt) {
        return overview.overview(CurrentUser.id(jwt));
    }

    @GetMapping("/api/projects/{projectId}/monitors")
    public List<OpsService.MonitorView> monitors(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return ops.monitors(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/api/projects/{projectId}/monitors")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createMonitor(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                           @RequestBody OpsService.NewMonitor body) {
        return Map.of("id", ops.createMonitor(CurrentUser.id(jwt), projectId, body));
    }

    /** {@code {"paused": true, "until": "..."}} 로 중지, {@code {"paused": false}} 로 다시 켠다. */
    public record PauseRequest(boolean paused, Instant until) {
    }

    @PatchMapping("/api/monitors/{monitorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@AuthenticationPrincipal Jwt jwt, @PathVariable long monitorId, @RequestBody PauseRequest body) {
        ops.pause(CurrentUser.id(jwt), monitorId, body.until(), !body.paused());
    }

    @DeleteMapping("/api/monitors/{monitorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMonitor(@AuthenticationPrincipal Jwt jwt, @PathVariable long monitorId) {
        ops.deleteMonitor(CurrentUser.id(jwt), monitorId);
    }

    @GetMapping("/api/monitors/{monitorId}/incidents")
    public List<OpsService.IncidentView> incidents(@AuthenticationPrincipal Jwt jwt, @PathVariable long monitorId) {
        return ops.incidents(CurrentUser.id(jwt), monitorId);
    }

    @GetMapping("/api/projects/{projectId}/alert-rules")
    public List<OpsService.RuleView> rules(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return ops.rules(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/api/projects/{projectId}/alert-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createRule(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @RequestBody OpsService.NewRule body) {
        return Map.of("id", ops.createRule(CurrentUser.id(jwt), projectId, body));
    }

    public record RuleToggle(boolean enabled) {
    }

    @PatchMapping("/api/alert-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleRule(@AuthenticationPrincipal Jwt jwt, @PathVariable long ruleId, @RequestBody RuleToggle body) {
        ops.setRuleEnabled(CurrentUser.id(jwt), ruleId, body.enabled());
    }

    @GetMapping("/api/projects/{projectId}/notifications")
    public List<OpsService.NotificationView> notifications(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return ops.notifications(CurrentUser.id(jwt), projectId);
    }
}
