package com.muse.meomuneum.feature.auth.login.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String FIND_BY_EMAIL = """
            SELECT id, email, password_hash, deleted_at
            FROM users
            WHERE email = ?
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbcTemplate.query(FIND_BY_EMAIL, this::mapUser, email).stream().findFirst();
    }

    private UserAccount mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UserAccount(
                resultSet.getLong("id"),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                resultSet.getTimestamp("deleted_at") == null
                        ? null
                        : resultSet.getTimestamp("deleted_at").toLocalDateTime());
    }
}
