package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;

@Repository
public class DirectAuthReceiptRepository {
    private final JdbcTemplate jdbc;

    public record Receipt(String flowId, String operationId, long epoch, long originalGeneration,
            byte[] originalProof, long resultEpoch, long resultRevision, long resultGeneration, String encrypted,
            String status, LocalDateTime expiry) {
    }

    public DirectAuthReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Receipt> lockLogout(Tuple tuple) {
        return findLogout(tuple, true);
    }

    public Optional<Receipt> findLogout(Tuple tuple, boolean lock) {
        return jdbc
                .query("""
                        SELECT * FROM auth_direct_operation_receipts
                        WHERE flow_id = ? AND operation_kind = 'LOGOUT' AND operation_id = ?
                        """ + (lock ? " FOR UPDATE" : ""),
                        (row, index) -> new Receipt(row.getString("flow_id"), row.getString("operation_id"),
                                row.getLong("starting_epoch"), row.getLong("original_flow_cookie_generation"),
                                row.getBytes("original_flow_cookie_hmac"), row.getLong("result_epoch"),
                                row.getLong("result_revision"), row.getLong("result_flow_cookie_generation"),
                                row.getString("encrypted_result_response_body"), row.getString("status"),
                                row.getObject("receipt_expires_at", LocalDateTime.class)),
                        tuple.flowId(), tuple.operationId())
                .stream().findFirst();
    }

    public void insertLogout(DirectAuthFlowState flow, Tuple tuple, byte[] proof, long epoch, long revision,
            long generation, String encryptedBody, String encryptedFlow) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_direct_operation_receipts WHERE flow_id = ?
                AND created_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND)
                """, Integer.class, flow.flowId());
        if (count == null || count >= 20) {
            throw new DirectAuthFailure(429, "AUTH_OPERATION_RECEIPT_LIMIT");
        }
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_direct_operation_receipts
                (flow_id, operation_kind, operation_id, starting_epoch, original_flow_cookie_generation,
                 original_flow_cookie_hmac, result_epoch, result_revision, result_flow_cookie_generation,
                 status, receipt_expires_at, tombstone_purge_at, encrypted_result_response_body,
                 encrypted_result_flow_cookie, created_at, completed_at)
                VALUES (?, 'LOGOUT', ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED',
                        DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND),
                        DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 87300 SECOND), ?, ?,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, flow.flowId(), tuple.operationId(), tuple.epoch(), flow.flowCookieGeneration(), proof,
                epoch, revision, generation, encryptedBody, encryptedFlow));
    }

    public void logout(DirectAuthFlowState flow, String operationId, String hash, LocalDateTime expiry) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET flow_epoch = flow_epoch + 1, revision = revision + 1,
                lifecycle = 'ANONYMOUS', active_session_sid = NULL,
                active_login_operation_id = NULL, flow_cookie_hash = ?,
                flow_cookie_generation = flow_cookie_generation + 1, cookie_expires_at = ?,
                current_direct_operation_kind = 'LOGOUT', current_direct_operation_id = ?,
                current_refresh_recovery_operation_id = NULL, current_refresh_recovery_source_operation_id = NULL,
                current_refresh_recovery_status = NULL, current_refresh_recovery_lease_expires_at = NULL,
                current_refresh_recovery_claim_flow_cookie_generation = NULL,
                current_refresh_recovery_claim_refresh_generation = NULL,
                current_refresh_recovery_result_flow_cookie_generation = NULL,
                current_refresh_recovery_result_refresh_generation = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND flow_epoch = ? AND revision = ? AND flow_cookie_generation = ?
                """, hash, expiry, operationId, flow.flowId(), flow.epoch(), flow.revision(),
                flow.flowCookieGeneration()));
        if (flow.activeLoginOperationId() != null) {
            DirectAuthProjectionRepository.requireOne(jdbc.update("""
                    UPDATE auth_login_operations SET status = 'SUPERSEDED', owner_token = NULL,
                    lease_expires_at = NULL, result_epoch = ?, result_revision = ?, completed_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND operation_id = ? AND status = 'IN_PROGRESS'
                    """, Math.incrementExact(flow.epoch()), Math.incrementExact(flow.revision()),
                    flow.flowId(), flow.activeLoginOperationId()));
        }
    }
}
