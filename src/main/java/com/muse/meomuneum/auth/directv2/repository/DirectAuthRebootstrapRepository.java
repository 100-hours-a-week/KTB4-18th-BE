package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.model.DirectAuthOperationState;

@Repository
public class DirectAuthRebootstrapRepository {
    public record Receipt(long epoch, Long binding, String status, Long resultEpoch, Long revision,
            Long generation, LocalDateTime expiry, String encrypted) {
    }

    private final JdbcTemplate jdbc;

    public DirectAuthRebootstrapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Receipt> lock(Tuple tuple) {
        return jdbc
                .query("""
                        SELECT * FROM auth_flow_rebootstrap_operations WHERE flow_id = ? AND operation_id = ? FOR UPDATE
                        """,
                        (row, index) -> new Receipt(row.getLong("starting_epoch"),
                                row.getObject("binding_generation", Long.class),
                                row.getString("status"), row.getObject("result_epoch", Long.class),
                                row.getObject("result_revision", Long.class),
                                row.getObject("result_flow_cookie_generation", Long.class),
                                row.getObject("receipt_expires_at", LocalDateTime.class),
                                row.getString("result_flow_cookie_ciphertext")),
                        tuple.flowId(), tuple.operationId())
                .stream().findFirst();
    }

    public boolean canonical(DirectAuthFlowState flow) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_flow_rebootstrap_operations WHERE flow_id = ? AND status = 'SUCCEEDED'
                AND result_epoch = ? AND result_revision = ? AND result_flow_cookie_generation = ?
                AND binding_generation = ? AND receipt_expires_at > CURRENT_TIMESTAMP(6)
                """, Long.class, flow.flowId(), flow.epoch(), flow.revision(), flow.flowCookieGeneration(),
                flow.bindingCookieGeneration());
        return count != null && count == 1;
    }

    public void quota(String flowId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_flow_rebootstrap_operations WHERE flow_id = ?
                AND created_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND)
                """, Long.class, flowId);
        if (count == null || count >= 20) {
            throw new DirectAuthFailure(429, "AUTH_OPERATION_RECEIPT_LIMIT");
        }
    }

    public void supersede(DirectAuthFlowState flow, DirectAuthOperationState operation, long epoch, long revision) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_login_operations SET status = 'SUPERSEDED', owner_token = NULL, lease_expires_at = NULL,
                result_epoch = ?, result_revision = ?, completed_at = CURRENT_TIMESTAMP(6), updated_at =
                CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND operation_id = ? AND starting_epoch = ? AND status = 'IN_PROGRESS'
                AND owner_token <=> ? AND proof_generation = ?
                """, epoch, revision, flow.flowId(), operation.operationId(), flow.epoch(), operation.ownerToken(),
                flow.flowCookieGeneration()));
    }

    public void rotate(DirectAuthFlowState flow, long epoch, String hash, LocalDateTime expiry) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET flow_epoch = ?, revision = revision + 1,
                lifecycle = CASE WHEN lifecycle = 'AUTHENTICATED' THEN lifecycle ELSE 'ANONYMOUS' END,
                flow_cookie_generation = flow_cookie_generation + 1, flow_cookie_hash = ?, cookie_expires_at = ?,
                active_login_operation_id = NULL, current_refresh_recovery_status = CASE
                    WHEN current_refresh_recovery_operation_id IS NULL THEN NULL ELSE 'RESULT_STALE' END,
                current_refresh_recovery_lease_expires_at = NULL,
                current_refresh_recovery_claim_flow_cookie_generation = NULL,
                current_refresh_recovery_claim_refresh_generation = NULL,
                current_refresh_recovery_result_flow_cookie_generation = NULL,
                current_refresh_recovery_result_refresh_generation = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND flow_epoch = ? AND revision = ? AND flow_cookie_generation = ?
                """, epoch, hash, expiry, flow.flowId(), flow.epoch(), flow.revision(), flow.flowCookieGeneration()));
    }

    public void save(Tuple tuple, DirectAuthFlowState flow, String encrypted) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_flow_rebootstrap_operations
                (flow_id, operation_id, starting_epoch, binding_generation, status, result_epoch, result_revision,
                 result_flow_cookie_generation, result_flow_cookie_ciphertext, receipt_expires_at,
                 tombstone_purge_at, created_at, completed_at)
                VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 900 SECOND),
                        DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 87300 SECOND), CURRENT_TIMESTAMP(6),
                        CURRENT_TIMESTAMP(6))
                """, flow.flowId(), tuple.operationId(), tuple.epoch(), flow.bindingCookieGeneration(), flow.epoch(),
                flow.revision(), flow.flowCookieGeneration(), encrypted));
    }
}
