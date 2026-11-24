package com.errdeck.api.in.web;

import org.springframework.security.oauth2.jwt.Jwt;

/** 액세스 토큰의 sub 가 사용자 ID다. */
final class CurrentUser {

    private CurrentUser() {
    }

    static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
