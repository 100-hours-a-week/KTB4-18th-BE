package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DirectAuthBootstrapRepository {
    private final JdbcTemplate jdbc;

    public record Receipt(String operationId, byte[] fingerprint, byte[] proof, String flowId, String status,
            long epoch, long revision, long flowGeneration, long bindingGeneration, String encrypted,
            LocalDateTime expiry) {
    }

    public DirectAuthBootstrapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(String operationId, byte[] fingerprint, byte[] proof) {
        return jdbc.update("""
                INSERT IGNORE INTO auth_flow_bootstrap_operations
                (operation_id, request_fingerprint, initial_proof_fingerprint, status,
                 receipt_expires_at, tombstone_purge_at, created_at, updated_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND),
                DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 87300 SECOND), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, operationId, fingerprint, proof) == 1;
    }

    public Optional<Receipt> lock(String operationId) {
        return jdbc.query("SELECT * FROM auth_flow_bootstrap_operations WHERE operation_id = ? FOR UPDATE",
                (row, index) -> new Receipt(row.getString("operation_id"), row.getBytes("request_fingerprint"),
                        row.getBytes("initial_proof_fingerprint"), row.getString("flow_id"), row.getString("status"),
                        row.getLong("result_epoch"), row.getLong("result_revision"),
                        row.getLong("result_flow_cookie_generation"), row.getLong("result_binding_cookie_generation"),
                        row.getString("encrypted_exact_response"),
                        row.getObject("receipt_expires_at", LocalDateTime.class)),
                operationId).stream().findFirst();
    }

    public void complete(Receipt receipt, String flowId, String lifecycle, long epoch, long revision,
            long flowGeneration, long bindingGeneration, String encrypted) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_flow_bootstrap_operations SET flow_id = ?, status = 'SUCCEEDED', result_flow_status = ?,
                result_epoch = ?, result_revision = ?, result_flow_cookie_generation = ?,
                result_binding_cookie_generation = ?, encrypted_exact_response = ?, completed_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6) WHERE operation_id = ? AND status = 'IN_PROGRESS'
                AND receipt_expires_at > CURRENT_TIMESTAMP(6)
                """, flowId, lifecycle, epoch, revision, flowGeneration, bindingGeneration, encrypted,
                receipt.operationId()));
    }

    public void rateLimit(String addressHash) {
        LocalDateTime window = jdbc.queryForObject(
                "SELECT TIMESTAMP(DATE_FORMAT(CURRENT_TIMESTAMP(6), '%Y-%m-%d %H:%i:00'))", LocalDateTime.class);
        jdbc.update("""
                INSERT INTO auth_flow_bootstrap_rate_limits (address_hash, window_started_at, request_count, updated_at)
                VALUES (?, ?, 1, CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE
                request_count = request_count + 1, updated_at = CURRENT_TIMESTAMP(6)
                """, addressHash, window);
        Integer count = jdbc.queryForObject("""
                SELECT request_count FROM auth_flow_bootstrap_rate_limits
                WHERE address_hash = ? AND window_started_at = ?
                """, Integer.class, addressHash, window);
        if (count == null || count > 20) {
            throw new com.muse.meomuneum.auth.directv2.model.DirectAuthFailure(429,
                    "AUTH_FLOW_BOOTSTRAP_RATE_LIMITED");
        }
    }
}
