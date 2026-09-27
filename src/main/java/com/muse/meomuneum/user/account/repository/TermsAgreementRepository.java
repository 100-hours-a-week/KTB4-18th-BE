package com.muse.meomuneum.user.account.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TermsAgreementRepository {

    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final JdbcTemplate jdbc;

    public TermsAgreementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AgreementRow> findAllByUserId(long userId) {
        return jdbc.query("""
                SELECT id, terms_id, created_at, deleted_at
                FROM terms_agreements
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                """, (row, index) -> new AgreementRow(
                row.getLong("id"),
                row.getLong("terms_id"),
                format(row.getTimestamp("created_at")),
                format(row.getTimestamp("deleted_at"))), userId);
    }

    public Set<Long> findActiveTermsIds(long userId, Collection<Long> termsIds) {
        if (termsIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = termsIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        Object[] parameters = new Object[termsIds.size() + 1];
        parameters[0] = userId;
        int index = 1;
        for (Long termsId : termsIds) {
            parameters[index++] = termsId;
        }
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT terms_id
                FROM terms_agreements
                WHERE user_id = ? AND deleted_at IS NULL AND terms_id IN (%s)
                """.formatted(placeholders), Long.class, parameters));
    }

    public long create(long userId, long termsId, Instant agreedAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO terms_agreements (user_id, terms_id, created_at)
                    VALUES (?, ?, ?)
                    """, new String[]{"id"});
            statement.setLong(1, userId);
            statement.setLong(2, termsId);
            statement.setTimestamp(3, Timestamp.from(agreedAt));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Generated terms agreement id is missing");
        }
        return key.longValue();
    }

    public int withdrawActive(long userId, long termsId, Instant withdrawnAt) {
        return jdbc.update("""
                UPDATE terms_agreements
                SET deleted_at = ?
                WHERE user_id = ? AND terms_id = ? AND deleted_at IS NULL
                """, Timestamp.from(withdrawnAt), userId, termsId);
    }

    private String format(Timestamp timestamp) {
        return timestamp == null ? null : UTC_FORMAT.format(timestamp.toInstant());
    }

    public record AgreementRow(long agreementId, long termsId, String agreedAt, String withdrawnAt) {
    }
}
