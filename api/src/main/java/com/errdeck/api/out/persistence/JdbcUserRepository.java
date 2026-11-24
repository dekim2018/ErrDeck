package com.errdeck.api.out.persistence;

import com.errdeck.api.app.ApiException;
import com.errdeck.api.app.UserRepository;
import com.errdeck.api.domain.User;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 첫 사용자를 관리자로 만든다. 동시에 두 명이 처음 가입해도 표 잠금으로 한 명만 관리자가 된다. */
    @Override
    public User create(String email, String passwordHash) {
        try {
            return jdbc.sql("""
                            WITH lock AS (SELECT pg_advisory_xact_lock(hashtext('errdeck:first-user')))
                            INSERT INTO users (email, password_hash, role)
                            SELECT ?, ?, CASE WHEN EXISTS (SELECT 1 FROM users) THEN 'USER' ELSE 'ADMIN' END FROM lock
                            RETURNING id, email, role, created_at
                            """)
                    .params(email, passwordHash)
                    .query((rs, n) -> new User(rs.getLong("id"), rs.getString("email"), "ADMIN".equals(rs.getString("role")),
                            rs.getTimestamp("created_at").toInstant()))
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ApiException.Kind.CONFLICT, "이미 가입한 이메일이다");
        }
    }

    @Override
    public Optional<Credentials> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, role, created_at, password_hash FROM users WHERE email = ?")
                .param(email)
                .query((rs, n) -> new Credentials(new User(rs.getLong("id"), rs.getString("email"), "ADMIN".equals(rs.getString("role")),
                        rs.getTimestamp("created_at").toInstant()), rs.getString("password_hash")))
                .optional();
    }

    @Override
    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT id, email, role, created_at FROM users WHERE id = ?")
                .param(id)
                .query((rs, n) -> new User(rs.getLong("id"), rs.getString("email"), "ADMIN".equals(rs.getString("role")),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
