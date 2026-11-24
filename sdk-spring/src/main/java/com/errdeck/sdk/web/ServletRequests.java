package com.errdeck.sdk.web;

import com.errdeck.sdk.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 서블릿 요청에서 보낼 값만 복사한다. 요청 객체는 응답이 끝나면 재사용되므로 참조를 들고 있지 않는다. */
public final class ServletRequests {

    private ServletRequests() {
    }

    public static RequestContext snapshot(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        Principal principal = request.getUserPrincipal();
        return new RequestContext(request.getMethod(), request.getRequestURL().toString(), request.getQueryString(), headers,
                principal == null ? null : principal.getName());
    }

    /** {@code ErrDeck.capture(e)} 를 요청을 처리하는 중에 부르면 그 요청의 정보를 붙인다. */
    public static RequestContext current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return snapshot(servlet.getRequest());
        }
        return null;
    }
}
