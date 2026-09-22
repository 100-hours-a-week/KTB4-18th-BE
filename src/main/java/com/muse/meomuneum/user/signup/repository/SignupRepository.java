package com.muse.meomuneum.user.signup.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.user.domain.UserRole;

@Repository
public class SignupRepository {
    private final JdbcTemplate jdbc;

    public SignupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsUserByEmail(String email) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    public List<Term> findTermsByIds(Collection<Long> ids, Instant now) {
        String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        return jdbc.query("""
                SELECT id, type
                FROM terms
                WHERE id IN (%s) AND effective_at <= ?
                """.formatted(placeholders), (row, index) -> new Term(row.getLong("id"), row.getString("type")),
                values(ids, now));
    }

    public long createUser(
            String email,
            String passwordHash,
            String nickname,
            Short birthYear,
            String gender,
            Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (email, password_hash, nickname, birth_year, gender, role, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setString(3, nickname);
            statement.setObject(4, birthYear);
            statement.setString(5, gender);
            statement.setString(6, UserRole.USER.name());
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Generated user id is missing");
        }
        return key.longValue();
    }

    public void createTermsAgreement(long userId, long termsId, Instant now) {
        jdbc.update("""
                INSERT INTO terms_agreements (user_id, terms_id, created_at)
                VALUES (?, ?, ?)
                """, userId, termsId, Timestamp.from(now));
    }

    private Object[] values(Collection<Long> ids, Instant now) {
        Object[] values = new Object[ids.size() + 1];
        int index = 0;
        for (Long id : ids) {
            values[index++] = id;
        }
        values[index] = Timestamp.from(now);
        return values;
    }

    public record Term(long id, String type) {}
}
