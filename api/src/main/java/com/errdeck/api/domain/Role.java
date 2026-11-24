package com.errdeck.api.domain;

/**
 * 시스템 안의 역할(15.4). 뒤로 갈수록 할 수 있는 일이 많고, 앞의 역할이 할 수 있는 일을 모두 할 수 있다.
 *
 * <table>
 *   <tr><th>역할</th><th>조회</th><th>이슈 상태 변경, 댓글</th><th>알림 규칙, 모니터, 릴리스</th><th>키와 토큰, 구성원, 한도</th></tr>
 *   <tr><td>VIEWER</td><td>가능</td><td>불가</td><td>불가</td><td>불가</td></tr>
 *   <tr><td>MEMBER</td><td>가능</td><td>가능</td><td>불가</td><td>불가</td></tr>
 *   <tr><td>ADMIN</td><td>가능</td><td>가능</td><td>가능</td><td>불가</td></tr>
 *   <tr><td>OWNER</td><td>가능</td><td>가능</td><td>가능</td><td>가능</td></tr>
 * </table>
 */
public enum Role {
    VIEWER, MEMBER, ADMIN, OWNER;

    public boolean atLeast(Role required) {
        return compareTo(required) >= 0;
    }
}
