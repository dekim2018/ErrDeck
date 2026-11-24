package com.errdeck.api.in.web;

import com.errdeck.api.app.IssueQueryService;
import com.errdeck.api.app.IssueQueryService.Period;
import com.errdeck.api.app.IssueQueryService.Sort;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 이슈 목록과 상세(12.3). 조회라 VIEWER 이상이다. */
@RestController
public class IssueQueryController {

    private final IssueQueryService issues;

    public IssueQueryController(IssueQueryService issues) {
        this.issues = issues;
    }

    /**
     * @param status  unresolved, resolved, ignored, regressed. 비우면 모두
     * @param release 최근 릴리스가 이것인 이슈만
     * @param period  횟수와 사용자를 셀 기간. 1h, 24h, 7d, 14d, 30d
     * @param sort    recent(최근 발생), frequent(횟수), users(영향 사용자)
     */
    @GetMapping("/api/projects/{projectId}/issues")
    public List<IssueQueryService.IssueSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String release,
                                                     @RequestParam(defaultValue = "24h") String period,
                                                     @RequestParam(defaultValue = "recent") String sort,
                                                     @RequestParam(defaultValue = "0") int page) {
        return issues.list(CurrentUser.id(jwt), projectId, blankToNull(status), blankToNull(release), Period.parse(period),
                parseSort(sort), page);
    }

    @GetMapping("/api/issues/{issueId}")
    public IssueQueryService.IssueDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long issueId,
                                                @RequestParam(defaultValue = "24h") String period) {
        return issues.detail(CurrentUser.id(jwt), issueId, Period.parse(period));
    }

    private static Sort parseSort(String sort) {
        try {
            return Sort.valueOf(sort.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.errdeck.api.app.ApiException(com.errdeck.api.app.ApiException.Kind.BAD_REQUEST,
                    "정렬은 recent, frequent, users 중 하나다");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
