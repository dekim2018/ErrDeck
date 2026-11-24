package com.errdeck.api.domain;

import java.time.Instant;

/**
 * 콘솔 사용자.
 *
 * @param admin 시스템을 만들 수 있는지. 처음 가입한 사용자가 관리자가 된다(CHOICES 11-1)
 */
public record User(long id, String email, boolean admin, Instant createdAt) {
}
