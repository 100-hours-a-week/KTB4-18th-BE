package com.muse.meomuneum.user.signup.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

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

    public List<Term> findCurrentSignupTerms(Instant now) {
        return jdbc.query("""
                SELECT current_terms.id, current_terms.type, current_terms.is_required
                FROM (
                    SELECT t.id, t.type, t.is_required,
                        ROW_NUMBER() OVER (PARTITION BY t.type ORDER BY t.effective_at DESC, t.id DESC) AS rank_number
                    FROM terms t
                    WHERE t.type IN ('SERVICE', 'PROFILE', 'AIPERSONAL', 'LOCATIONTERMS', 'LOCATION', 'PRIVACY')
                        AND t.effective_at <= ?
                ) current_terms
                WHERE current_terms.rank_number = 1
                """, (row, index) -> new Term(
                row.getLong("id"),
                row.getString("type"),
                row.getBoolean("is_required")), Timestamp.valueOf(LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
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

    public record Term(long id, String type, boolean required) {
    }
}
