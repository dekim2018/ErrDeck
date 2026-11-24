package com.errdeck.sdk.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * 가장 먼저 불리는 예외 리졸버. 예외를 요청 속성에 적어 두기만 하고 처리는 다음 리졸버에 넘긴다(11.2).
 * 보낼지는 응답이 끝난 뒤 {@link ErrDeckServletFilter} 가 최종 상태 코드를 보고 정한다.
 * 감시 대상은 대부분 전역 예외 처리기로 예외를 응답으로 바꾸므로, 처리되지 않은 것만 잡으면 거의 아무것도 잡히지 않는다.
 */
public class ErrDeckExceptionResolver implements HandlerExceptionResolver, Ordered {

    static final String ATTRIBUTE = ErrDeckExceptionResolver.class.getName() + ".exception";

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            request.setAttribute(ATTRIBUTE, ex);
        } catch (RuntimeException ignored) {
            // 감시 대상의 예외 처리를 방해하지 않는다.
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
