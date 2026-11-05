package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository.Session;

@Repository
public class DirectAuthRefreshRepository {
    private final JdbcTemplate jdbc;

    public record Receipt(String flowId, String id, long epoch, Long originalGeneration, byte[] originalFlow,
            byte[] originalRefresh, String sid, Long resultEpoch, Long resultRevision, Long flowGeneration,
            Long refreshGeneration, String status, Long client, Long effective, LocalDateTime expiry,
            String encryptedBody, String encryptedFlow, String encryptedRefresh) {
    }

    public DirectAuthRefreshRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Receipt> find(String flowId, String id, boolean lock) {
        return jdbc.query("""
                SELECT * FROM auth_direct_operation_receipts
                WHERE flow_id = ? AND operation_kind = 'REFRESH' AND operation_id = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, index) -> new Receipt(row.getString("flow_id"),
                row.getString("operation_id"), row.getLong("starting_epoch"),
                row.getObject("original_flow_cookie_generation", Long.class),
                row.getBytes("original_flow_cookie_hmac"), row.getBytes("original_refresh_token_hmac"),
                row.getString("result_session_sid"), row.getObject("result_epoch", Long.class),
                row.getObject("result_revision", Long.class),
                row.getObject("result_flow_cookie_generation", Long.class),
                row.getObject("result_refresh_generation", Long.class), row.getString("status"),
                row.getObject("client_expires_at_ms", Long.class), row.getObject("effective_expires_at_ms", Long.class),
                row.getObject("receipt_expires_at", LocalDateTime.class),
                row.getString("encrypted_result_response_body"),
                row.getString("encrypted_result_flow_cookie"), row.getString("encrypted_result_refresh_token")),
                flowId, id).stream().findFirst();
    }

    public void quota(String flowId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_direct_operation_receipts WHERE flow_id = ? AND operation_kind = 'REFRESH'
                AND created_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND)
                """, Integer.class, flowId);
        if (count == null || count >= 20) {
            throw DirectAuthFailure.unavailable();
        }
    }

    public void negative(Tuple tuple) {
        quota(tuple.flowId());
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_direct_operation_receipts
                (flow_id, operation_kind, operation_id, starting_epoch, status, receipt_expires_at,
                 tombstone_purge_at, created_at, completed_at)
                VALUES (?, 'REFRESH', ?, ?, 'NEGATIVE_STALE', CURRENT_TIMESTAMP(6),
                 DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 86400 SECOND), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, tuple.flowId(), tuple.operationId(), tuple.epoch()));
    }

    public void save(Receipt receipt) {
        quota(receipt.flowId());
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_direct_operation_receipts
                (flow_id, operation_kind, operation_id, starting_epoch, original_flow_cookie_generation,
                 original_flow_cookie_hmac, original_refresh_token_hmac, result_session_sid,
                 result_epoch, result_revision, result_flow_cookie_generation, result_refresh_generation,
                 status, client_expires_at_ms, effective_expires_at_ms, receipt_expires_at,
                 tombstone_purge_at, encrypted_result_response_body, encrypted_result_flow_cookie,
                 encrypted_result_refresh_token, created_at, completed_at)
                VALUES (?, 'REFRESH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?,
                        TIMESTAMPADD(SECOND, 86400, ?), ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, receipt.flowId(), receipt.id(), receipt.epoch(), receipt.originalGeneration(),
                receipt.originalFlow(), receipt.originalRefresh(), receipt.sid(), receipt.resultEpoch(),
                receipt.resultRevision(), receipt.flowGeneration(), receipt.refreshGeneration(), receipt.client(),
                receipt.effective(), receipt.expiry(), receipt.expiry(), receipt.encryptedBody(),
                receipt.encryptedFlow(), receipt.encryptedRefresh()));
    }

    public void rotate(DirectAuthFlowState flow, Session session, Tuple tuple, String flowHash, LocalDateTime idle,
            String refreshHash, String encryptedRefresh, String accessJti, LocalDateTime accessExpiry, long effective) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_sessions SET flow_epoch = flow_epoch + 1, refresh_generation =
                refresh_generation + 1,
                current_refresh_token_hash = ?, encrypted_current_refresh_token = ?, idle_expires_at = ?,
                direct_projection_access_jti = ?, direct_projection_access_expires_at = ?, updated_at =
                CURRENT_TIMESTAMP(6)
                WHERE sid = ? AND flow_id = ? AND flow_epoch = ? AND status = 'ACTIVE' AND refresh_generation = ?
                AND refresh_cookie_mode = 'GENERATION_SCOPED' AND idle_expires_at > CURRENT_TIMESTAMP(6)
                AND absolute_expires_at > CURRENT_TIMESTAMP(6)
                AND CURRENT_TIMESTAMP(6) < TIMESTAMPADD(MICROSECOND, ? * 1000, CAST('1970-01-01' AS DATETIME(6)))
                """, refreshHash, encryptedRefresh, idle, accessJti, accessExpiry, session.sid(), flow.flowId(),
                flow.epoch(), session.refreshGeneration(), effective));
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET flow_epoch = flow_epoch + 1, revision = revision + 1,
                flow_cookie_generation = flow_cookie_generation + 1, flow_cookie_hash = ?, cookie_expires_at = ?,
                current_direct_operation_kind = 'REFRESH', current_direct_operation_id = ?,
                active_login_operation_id = NULL, current_refresh_recovery_operation_id = NULL,
                current_refresh_recovery_source_operation_id = NULL, current_refresh_recovery_status = NULL,
                current_refresh_recovery_lease_expires_at = NULL,
                current_refresh_recovery_claim_flow_cookie_generation = NULL,
                current_refresh_recovery_claim_refresh_generation = NULL,
                current_refresh_recovery_result_flow_cookie_generation = NULL,
                current_refresh_recovery_result_refresh_generation = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND flow_epoch = ? AND revision = ? AND flow_cookie_generation = ?
                AND active_session_sid = ? AND lifecycle = 'AUTHENTICATED'
                AND CURRENT_TIMESTAMP(6) < TIMESTAMPADD(MICROSECOND, ? * 1000, CAST('1970-01-01' AS DATETIME(6)))
                """, flowHash, idle, tuple.operationId(), flow.flowId(), flow.epoch(), flow.revision(),
                flow.flowCookieGeneration(), session.sid(), effective));
    }
}
