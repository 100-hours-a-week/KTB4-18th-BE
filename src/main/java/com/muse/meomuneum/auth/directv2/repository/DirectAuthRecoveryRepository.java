package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository.Receipt;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository.Session;

@Repository
public class DirectAuthRecoveryRepository {
    public record Recovery(String flowId, String id, String sourceId, long epoch, Long bindingGeneration,
            Long claimFlowGeneration, Long claimRefreshGeneration, String status, byte[] owner,
            LocalDateTime lease, Long resultEpoch, Long resultRevision, String sid, Long flowGeneration,
            Long refreshGeneration, Long client, Long effective, LocalDateTime expiry, String encrypted) {
    }

    private final JdbcTemplate jdbc;

    public DirectAuthRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Recovery> find(String flowId, String id, boolean lock) {
        return jdbc.query("""
                SELECT * FROM auth_refresh_recovery_operations WHERE flow_id = ? AND recovery_operation_id = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, index) -> new Recovery(row.getString("flow_id"),
                row.getString("recovery_operation_id"), row.getString("source_refresh_operation_id"),
                row.getLong("starting_epoch"), row.getObject("binding_generation", Long.class),
                row.getObject("claim_flow_cookie_generation", Long.class),
                row.getObject("claim_refresh_generation", Long.class),
                row.getString("status"), row.getBytes("owner_token"),
                row.getObject("lease_expires_at", LocalDateTime.class),
                row.getObject("result_epoch", Long.class), row.getObject("result_revision", Long.class),
                row.getString("result_session_sid"),
                row.getObject("result_flow_cookie_generation", Long.class),
                row.getObject("result_refresh_generation", Long.class),
                row.getObject("client_expires_at_ms", Long.class), row.getObject("effective_expires_at_ms", Long.class),
                row.getObject("receipt_expires_at", LocalDateTime.class),
                row.getString("encrypted_result_response_body")),
                flowId, id).stream().findFirst();
    }

    public void insert(DirectAuthFlowState flow, Session session, Receipt source, String id, byte[] owner,
            long client, long effective, LocalDateTime expiry) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_refresh_recovery_operations WHERE flow_id = ?
                AND created_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND)
                """, Long.class, flow.flowId());
        if (count == null || count >= 20) {
            throw new DirectAuthFailure(429, "AUTH_OPERATION_RECEIPT_LIMIT");
        }
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_refresh_recovery_operations
                (flow_id, recovery_operation_id, source_refresh_operation_id, starting_epoch,
                 binding_generation, claim_flow_cookie_generation, claim_refresh_generation, status, owner_token,
                 lease_expires_at, client_expires_at_ms, effective_expires_at_ms, receipt_expires_at,
                 tombstone_purge_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND),
                        ?, ?, ?, TIMESTAMPADD(SECOND, 86400, ?), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, flow.flowId(), id, source.id(), source.epoch(), flow.bindingCookieGeneration(),
                flow.flowCookieGeneration(), session.refreshGeneration(), owner, client, effective, expiry, expiry));
    }

    public void reclaim(DirectAuthFlowState flow, Session session, Recovery row, byte[] owner) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_refresh_recovery_operations SET owner_token = ?,
                lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND),
                claim_flow_cookie_generation = ?, claim_refresh_generation = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND recovery_operation_id = ? AND source_refresh_operation_id = ? AND
                starting_epoch = ?
                AND binding_generation = ? AND status = 'IN_PROGRESS' AND lease_expires_at <= CURRENT_TIMESTAMP(6)
                AND receipt_expires_at > CURRENT_TIMESTAMP(6) AND effective_expires_at_ms IS NOT NULL
                AND CURRENT_TIMESTAMP(6) < TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000,
                        CAST('1970-01-01' AS DATETIME(6)))
                """, owner, flow.flowCookieGeneration(), session.refreshGeneration(), flow.flowId(), row.id(),
                row.sourceId(),
                row.epoch(), flow.bindingCookieGeneration()));
    }

    public void pointer(DirectAuthFlowState flow, Session session, Recovery row) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET current_refresh_recovery_operation_id = ?,
                current_refresh_recovery_source_operation_id = ?, current_refresh_recovery_status = 'IN_PROGRESS',
                current_refresh_recovery_lease_expires_at = ?,
                current_refresh_recovery_claim_flow_cookie_generation = ?,
                current_refresh_recovery_claim_refresh_generation = ?,
                current_refresh_recovery_result_flow_cookie_generation = NULL,
                current_refresh_recovery_result_refresh_generation = NULL WHERE flow_id = ? AND flow_epoch = ?
                AND revision = ? AND current_direct_operation_kind = 'REFRESH' AND current_direct_operation_id = ?
                AND flow_cookie_generation = ? AND active_session_sid = ? AND binding_cookie_generation = ?
                """, row.id(), row.sourceId(), row.lease(), flow.flowCookieGeneration(), session.refreshGeneration(),
                flow.flowId(), flow.epoch(), flow.revision(), row.sourceId(), flow.flowCookieGeneration(),
                session.sid(),
                flow.bindingCookieGeneration()));
    }

    public void supersede(Recovery row) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_refresh_recovery_operations SET status = 'SUPERSEDED', owner_token = NULL,
                lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND recovery_operation_id = ? AND status = 'IN_PROGRESS'
                """, row.flowId(), row.id()));
    }

    public void complete(DirectAuthFlowState flow, Session session, Recovery row, byte[] owner,
            long revision, long flowGeneration, long refreshGeneration, String encryptedBody,
            String encryptedFlow, String encryptedRefresh) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_refresh_recovery_operations SET status = 'SUCCEEDED', owner_token = NULL,
                lease_expires_at = NULL, result_session_sid = ?, result_epoch = ?, result_revision = ?,
                result_flow_cookie_generation = ?, result_refresh_generation = ?, encrypted_result_response_body = ?,
                encrypted_result_flow_cookie = ?, encrypted_result_refresh_cookie = ?, completed_at =
                CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND recovery_operation_id = ?
                AND source_refresh_operation_id = ? AND starting_epoch = ? AND status = 'IN_PROGRESS' AND
                owner_token = ?
                AND binding_generation = ? AND claim_flow_cookie_generation = ? AND claim_refresh_generation = ?
                AND lease_expires_at > CURRENT_TIMESTAMP(6) AND receipt_expires_at > CURRENT_TIMESTAMP(6)
                AND client_expires_at_ms IS NOT NULL AND effective_expires_at_ms IS NOT NULL
                AND CURRENT_TIMESTAMP(6) < TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000,
                        CAST('1970-01-01' AS DATETIME(6)))
                """, session.sid(), flow.epoch(), revision, flowGeneration, refreshGeneration, encryptedBody,
                encryptedFlow,
                encryptedRefresh, flow.flowId(), row.id(), row.sourceId(), row.epoch(), owner,
                flow.bindingCookieGeneration(),
                flow.flowCookieGeneration(), session.refreshGeneration()));
    }

    public void rotate(DirectAuthFlowState flow, Session session, Recovery row, String flowHash,
            String refreshHash, String encryptedRefresh, String jti, LocalDateTime accessExpiry) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_sessions SET refresh_generation = refresh_generation + 1,
                current_refresh_token_hash = ?, encrypted_current_refresh_token = ?, direct_projection_access_jti = ?,
                direct_projection_access_expires_at = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE sid = ? AND flow_id = ? AND flow_epoch = ? AND status = 'ACTIVE' AND refresh_generation = ?
                AND idle_expires_at > CURRENT_TIMESTAMP(6) AND absolute_expires_at > CURRENT_TIMESTAMP(6)
                AND CURRENT_TIMESTAMP(6) < TIMESTAMPADD(MICROSECOND, ? * 1000, CAST('1970-01-01' AS DATETIME(6)))
                """, refreshHash, encryptedRefresh, jti, accessExpiry, session.sid(), flow.flowId(), flow.epoch(),
                session.refreshGeneration(), row.effective()));
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET revision = revision + 1, flow_cookie_generation =
                flow_cookie_generation + 1,
                flow_cookie_hash = ?, current_refresh_recovery_status = 'SUCCEEDED',
                current_refresh_recovery_lease_expires_at = NULL,
                current_refresh_recovery_result_flow_cookie_generation = ?,
                current_refresh_recovery_result_refresh_generation = ?,
                updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND flow_epoch = ? AND revision = ?
                AND flow_cookie_generation = ? AND binding_cookie_generation = ? AND active_session_sid = ?
                AND current_direct_operation_kind = 'REFRESH' AND current_direct_operation_id = ?
                AND current_refresh_recovery_operation_id = ? AND current_refresh_recovery_source_operation_id = ?
                AND current_refresh_recovery_status = 'IN_PROGRESS'
                AND current_refresh_recovery_claim_flow_cookie_generation = ?
                AND current_refresh_recovery_claim_refresh_generation = ?
                """, flowHash, Math.incrementExact(flow.flowCookieGeneration()),
                Math.incrementExact(session.refreshGeneration()),
                flow.flowId(), flow.epoch(), flow.revision(), flow.flowCookieGeneration(),
                flow.bindingCookieGeneration(),
                session.sid(), row.sourceId(), row.id(), row.sourceId(), flow.flowCookieGeneration(),
                session.refreshGeneration()));
    }
}
