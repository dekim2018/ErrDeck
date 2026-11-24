package com.errdeck.api.app;

import com.errdeck.api.domain.User;
import java.util.Optional;

public interface UserRepository {

    record Credentials(User user, String passwordHash) {
    }

    /**
     * 사용자를 만든다. 첫 사용자면 관리자로 만든다.
     *
     * @throws ApiException 이미 가입한 이메일이면 CONFLICT
     */
    User create(String email, String passwordHash);

    Optional<Credentials> findByEmail(String email);

    Optional<User> findById(long id);
}
