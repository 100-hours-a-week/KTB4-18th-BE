package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.model.DirectAuthOperationState;

@Repository
public class DirectAuthLoginLifecycleRepository {
    private final JdbcTemplate jdbc;
    private final DirectAuthOperationRepository operations;

    public DirectAuthLoginLifecycleRepository(JdbcTemplate jdbc, DirectAuthOperationRepository operations) {
        this.jdbc = jdbc;
        this.operations = operations;
    }

    public static long micros(LocalDateTime value) {
        return Math.addExact(Math.multiplyExact(value.toEpochSecond(ZoneOffset.UTC), 1_000_000L),
                value.getNano() / 1000);
    }

    public long effective(long client, LocalDateTime now) {
        long nowMicros = micros(now);
        long clientMicros;
        try {
            clientMicros = Math.multiplyExact(client, 1000);
        } catch (ArithmeticException invalid) {
            throw new DirectAuthFailure(400, "INVALID_CLIENT_EXPIRY");
        }
        long receiptMicros = Math.addExact(nowMicros, 900_000_000L);
        if (clientMicros <= nowMicros || clientMicros > receiptMicros) {
            throw new DirectAuthFailure(400, "INVALID_CLIENT_EXPIRY");
        }
        long effective = Math.min(clientMicros, receiptMicros) / 1000;
        if (effective * 1000 <= nowMicros) {
            throw new DirectAuthFailure(400, "INVALID_CLIENT_EXPIRY");
        }
        return effective;
    }

    public void claim(DirectAuthFlowState flow, Tuple tuple, long client, long effective, byte[] owner, byte[] proof) {
        DirectAuthProjectionRepository.requireOne(jdbc.update(
                """
                        INSERT INTO auth_login_operations
                        (flow_id, operation_id, starting_epoch, status, proof_generation, owner_token,
                         lease_expires_at, receipt_expires_at, tombstone_purge_at,
                         client_expires_at_ms, effective_expires_at_ms, original_flow_cookie_hmac, created_at,
                         updated_at)
                        VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND),
                         DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND),
                         DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 87300 SECOND), ?, ?, ?, CURRENT_TIMESTAMP(6),
                         CURRENT_TIMESTAMP(6))
                        """,
                flow.flowId(), tuple.operationId(), tuple.epoch(), flow.flowCookieGeneration(), owner,
                client, effective, proof));
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET active_login_operation_id = ?, revision = revision + 1,
                current_direct_operation_kind = 'LOGIN', current_direct_operation_id = ?,
                updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND flow_epoch = ? AND revision = ?
                AND lifecycle = 'ANONYMOUS' AND active_login_operation_id IS NULL AND flow_cookie_generation = ?
                """, tuple.operationId(), tuple.operationId(), flow.flowId(), tuple.epoch(), flow.revision(),
                flow.flowCookieGeneration()));
    }

    public void reject(DirectAuthFlowState flow, Tuple tuple, long client, long effective, String reason) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_login_operations WHERE flow_id = ? AND status = 'NOT_ADMITTED'
                AND created_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND)
                """, Integer.class, flow.flowId());
        if (count == null || count >= 20) {
            throw new DirectAuthFailure(429, "AUTH_OPERATION_RECEIPT_LIMIT");
        }
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_login_operations
                (flow_id, operation_id, starting_epoch, status, proof_generation, reason_code,
                 result_epoch, result_revision, receipt_expires_at, tombstone_purge_at,
                 client_expires_at_ms, effective_expires_at_ms, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, 'NOT_ADMITTED', ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND),
                 DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 87300 SECOND), ?, ?, CURRENT_TIMESTAMP(6),
                 CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, flow.flowId(), tuple.operationId(), tuple.epoch(), flow.flowCookieGeneration(), reason,
                flow.epoch(), flow.revision(), client, effective));
    }

    public boolean recoverExpired(DirectAuthFlowState flow, LocalDateTime now) {
        if (flow.activeLoginOperationId() == null) {
            return false;
        }
        var operation = operations.lockLogin(flow.flowId(), flow.activeLoginOperationId())
                .orElseThrow(DirectAuthFailure::unavailable);
        if (!"IN_PROGRESS".equals(operation.status())) {
            throw DirectAuthFailure.unavailable();
        }
        boolean expired = operation.leaseExpiresAt() == null || !now.isBefore(operation.leaseExpiresAt())
                || operation.effectiveExpiresAt() == null
                || micros(now) >= operation.effectiveExpiresAt() * 1000;
        if (!expired) {
            return false;
        }
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_login_operations SET status = 'REAUTH_REQUIRED', owner_token = NULL,
                lease_expires_at = NULL, result_epoch = ?, result_revision = ?, completed_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND operation_id = ? AND status = 'IN_PROGRESS'
                """, flow.epoch(), Math.incrementExact(flow.revision()), flow.flowId(), operation.operationId()));
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET revision = revision + 1, active_login_operation_id = NULL,
                updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND flow_epoch = ? AND revision = ?
                AND active_login_operation_id = ?
                """, flow.flowId(), flow.epoch(), flow.revision(), operation.operationId()));
        return true;
    }

    public void complete(DirectAuthFlowState flow, DirectAuthOperationState operation, byte[] owner,
            String status, long resultEpoch, long resultRevision, String sid, long generation) {
        if (!Arrays.equals(owner, operation.ownerToken())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_login_operations SET status = ?, owner_token = NULL, lease_expires_at = NULL,
                result_epoch = ?, result_revision = ?, result_sid = ?, result_flow_cookie_generation = ?,
                result_refresh_generation = ?, completed_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND operation_id = ? AND status = 'IN_PROGRESS' AND owner_token = ?
                AND starting_epoch = ? AND proof_generation = ? AND lease_expires_at > CURRENT_TIMESTAMP(6)
                AND receipt_expires_at > CURRENT_TIMESTAMP(6)
                AND effective_expires_at_ms IS NOT NULL AND client_expires_at_ms IS NOT NULL
                AND CURRENT_TIMESTAMP(6) < TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000,
                        CAST('1970-01-01 00:00:00' AS DATETIME(6)))
                """, status, resultEpoch, resultRevision, sid, generation, sid == null ? null : 1,
                flow.flowId(), operation.operationId(), owner, operation.startingEpoch(), operation.proofGeneration()));
    }

    public void activate(DirectAuthFlowState flow, String operationId, String sid, String cookieHash,
            LocalDateTime expiry) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET flow_epoch = flow_epoch + 1, revision = revision + 1,
                lifecycle = 'AUTHENTICATED', active_session_sid = ?,
                flow_cookie_hash = ?, flow_cookie_generation = flow_cookie_generation + 1, cookie_expires_at = ?,
                active_login_operation_id = NULL, current_direct_operation_kind = 'LOGIN',
                current_direct_operation_id = ?, current_refresh_recovery_operation_id = NULL,
                current_refresh_recovery_source_operation_id = NULL, current_refresh_recovery_status = NULL,
                current_refresh_recovery_lease_expires_at = NULL,
                current_refresh_recovery_claim_flow_cookie_generation = NULL,
                current_refresh_recovery_claim_refresh_generation = NULL,
                current_refresh_recovery_result_flow_cookie_generation = NULL,
                current_refresh_recovery_result_refresh_generation = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND flow_epoch = ? AND revision = ? AND active_login_operation_id = ?
                AND flow_cookie_generation = ? AND lifecycle = 'ANONYMOUS'
                """, sid, cookieHash, expiry, operationId, flow.flowId(), flow.epoch(), flow.revision(), operationId,
                flow.flowCookieGeneration()));
    }

    public void release(DirectAuthFlowState flow, String operationId) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET active_login_operation_id = NULL, revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND flow_epoch = ? AND revision = ?
                AND active_login_operation_id = ? AND flow_cookie_generation = ?
                """, flow.flowId(), flow.epoch(), flow.revision(), operationId, flow.flowCookieGeneration()));
    }
}
