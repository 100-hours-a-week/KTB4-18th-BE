package com.muse.meomuneum.user.signup.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TermsRepository {
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private final JdbcTemplate jdbc;

    public TermsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TermRow> findCurrent(Instant effectiveAt, String type) {
        return jdbc.query("""
                SELECT id, type, version, title, is_required, effective_at, content
                FROM (
                    SELECT t.*,
                        ROW_NUMBER() OVER (PARTITION BY t.type ORDER BY t.effective_at DESC, t.id DESC) AS rank_number
                    FROM terms t
                    WHERE t.effective_at <= ? AND (? IS NULL OR t.type = ?)
                ) current_terms
                WHERE rank_number = 1
                """, (row, index) -> mapRow(row),
                Timestamp.valueOf(LocalDateTime.ofInstant(effectiveAt, ZoneOffset.UTC)), type, type);
    }

    public Optional<TermRow> findById(long id) {
        return jdbc.query("""
                SELECT id, type, version, title, is_required, effective_at, content
                FROM terms WHERE id = ?
                """, (row, index) -> mapRow(row), id).stream().findFirst();
    }

    private TermRow mapRow(java.sql.ResultSet row) throws java.sql.SQLException {
        String effectiveAt = UTC_FORMAT.format(row.getTimestamp("effective_at")
                .toLocalDateTime().toInstant(ZoneOffset.UTC));
        return new TermRow(row.getLong("id"), row.getString("type"), row.getString("version"),
                row.getString("title"), row.getBoolean("is_required"), effectiveAt, row.getString("content"));
    }

    public record TermRow(long id, String type, String version, String title, boolean required,
            String effectiveAt, String content) {
    }
}
