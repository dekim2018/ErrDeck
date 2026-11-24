package com.errdeck.api.in.web;

import com.errdeck.api.app.ApiException;
import com.errdeck.api.app.PerformanceService;
import java.time.Duration;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 성능 화면(12.3): 엔드포인트 표, 느린 요청 목록, 추적 하나의 스팬 그림. */
@RestController
public class PerformanceController {

    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/api/projects/{projectId}/performance/endpoints")
    public PerformanceService.EndpointTable endpoints(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                                      @RequestParam(defaultValue = "24h") String period) {
        return performance.endpoints(CurrentUser.id(jwt), projectId, parse(period));
    }

    @GetMapping("/api/projects/{projectId}/performance/transactions")
    public List<PerformanceService.TransactionView> slowest(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                                            @RequestParam(required = false) String name,
                                                            @RequestParam(defaultValue = "24h") String period) {
        return performance.slowest(CurrentUser.id(jwt), projectId, name, parse(period));
    }

    @GetMapping("/api/traces/{traceId}")
    public List<PerformanceService.TransactionView> trace(@AuthenticationPrincipal Jwt jwt, @PathVariable String traceId) {
        return performance.trace(CurrentUser.id(jwt), traceId);
    }

    private static Duration parse(String period) {
        return switch (period) {
            case "1h" -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            default -> throw new ApiException(ApiException.Kind.BAD_REQUEST, "기간은 1h, 24h, 7d 중 하나다");
        };
    }
}
