package com.muse.meomuneum.auth.directv2.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthOperationRepository;

@Service
@ConditionalOnProperty(prefix = "auth.direct-v2", name = "enabled", havingValue = "true")
public class DirectAuthReceiptCleanupService {
    private final JdbcTemplate jdbc;
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthOperationRepository operations;
    private final DirectAuthLoginLifecycleRepository lifecycle;

    public DirectAuthReceiptCleanupService(JdbcTemplate jdbc, DirectAuthTransactionRunner transactions,
            DirectAuthFlowRepository flows, DirectAuthOperationRepository operations,
            DirectAuthLoginLifecycleRepository lifecycle) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.flows = flows;
        this.operations = operations;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${auth.direct-v2.cleanup-interval:60s}")
    public void clean() {
        try {
            cleanupBootstrap();
            cleanupLogin();
            cleanupLogout();
            cleanupProjection();
            cleanupRebootstrap();
        } catch (RuntimeException deferred) {
            return;
        }
    }

    private void cleanupBootstrap() {
        var ids = jdbc.queryForList("""
                SELECT operation_id FROM auth_flow_bootstrap_operations
                WHERE receipt_expires_at <= CURRENT_TIMESTAMP(6) AND status <> 'TOMBSTONE'
                OR tombstone_purge_at <= CURRENT_TIMESTAMP(6) ORDER BY operation_id LIMIT 20
                """, String.class);
        for (String id : ids) {
            transactions.run(() -> {
                var rows = jdbc.queryForList("""
                        SELECT operation_id FROM auth_flow_bootstrap_operations WHERE operation_id = ? FOR UPDATE
                        """, String.class, id);
                if (rows.isEmpty()) {
                    return null;
                }
                jdbc.update("""
                        UPDATE auth_flow_bootstrap_operations SET status = 'TOMBSTONE',
                        initial_proof_fingerprint = NULL, encrypted_exact_response = NULL,
                        result_flow_status = NULL, result_epoch = NULL, result_revision = NULL,
                        result_flow_cookie_generation = NULL, result_binding_cookie_generation = NULL,
                        updated_at = CURRENT_TIMESTAMP(6) WHERE operation_id = ?
                        AND receipt_expires_at <= CURRENT_TIMESTAMP(6)
                        """, id);
                jdbc.update("""
                        DELETE FROM auth_flow_bootstrap_operations WHERE operation_id = ?
                        AND tombstone_purge_at <= CURRENT_TIMESTAMP(6)
                        """, id);
                return null;
            });
        }
    }

    private void cleanupLogin() {
        var candidates = jdbc.query("""
                SELECT flow_id, operation_id FROM auth_login_operations
                WHERE status = 'IN_PROGRESS' AND lease_expires_at <= CURRENT_TIMESTAMP(6)
                OR status <> 'TOMBSTONE' AND (receipt_expires_at <= CURRENT_TIMESTAMP(6)
                    OR effective_expires_at_ms IS NOT NULL AND CURRENT_TIMESTAMP(6) >=
                    TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000, CAST('1970-01-01' AS DATETIME(6))))
                OR tombstone_purge_at <= CURRENT_TIMESTAMP(6) ORDER BY flow_id, operation_id LIMIT 20
                """, (row, index) -> new String[]{row.getString(1), row.getString(2)});
        for (var candidate : candidates) {
            transactions.run(() -> {
                var flow = flows.lock(candidate[0]).orElse(null);
                if (flow == null || operations.lockLogin(candidate[0], candidate[1]).isEmpty()) {
                    return null;
                }
                if (candidate[1].equals(flow.activeLoginOperationId())) {
                    lifecycle.recoverExpired(flow, flows.now());
                }
                jdbc.update("""
                        UPDATE auth_login_operations SET status = 'TOMBSTONE', owner_token = NULL,
                        lease_expires_at = NULL, proof_generation = NULL, original_flow_cookie_hmac = NULL,
                        result_sid = NULL, result_epoch = NULL, result_revision = NULL,
                        result_flow_cookie_generation = NULL, result_refresh_generation = NULL,
                        recovery_result_epoch = NULL, recovery_result_revision = NULL,
                        recovery_flow_cookie_generation = NULL, recovery_refresh_generation = NULL,
                        client_expires_at_ms = NULL, effective_expires_at_ms = NULL, reason_code = NULL,
                        updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND operation_id = ?
                        AND (receipt_expires_at <= CURRENT_TIMESTAMP(6) OR effective_expires_at_ms IS NOT NULL
                        AND CURRENT_TIMESTAMP(6) >= TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000,
                            CAST('1970-01-01' AS DATETIME(6))))
                        """, candidate[0], candidate[1]);
                Integer purged = jdbc.update("""
                        DELETE FROM auth_login_operations WHERE flow_id = ? AND operation_id = ?
                        AND tombstone_purge_at <= CURRENT_TIMESTAMP(6)
                        """, candidate[0], candidate[1]);
                if (purged == 1) {
                    jdbc.update("""
                            UPDATE auth_browser_flows SET current_direct_operation_kind = NULL,
                            current_direct_operation_id = NULL WHERE flow_id = ?
                            AND current_direct_operation_kind = 'LOGIN' AND current_direct_operation_id = ?
                            """, candidate[0], candidate[1]);
                }
                return null;
            });
        }
    }

    private void cleanupLogout() {
        var candidates = jdbc.query("""
                SELECT flow_id, operation_id FROM auth_direct_operation_receipts
                WHERE operation_kind = 'LOGOUT' AND (receipt_expires_at <= CURRENT_TIMESTAMP(6)
                AND status <> 'TOMBSTONE' OR tombstone_purge_at <= CURRENT_TIMESTAMP(6))
                ORDER BY flow_id, operation_id LIMIT 20
                """, (row, index) -> new String[]{row.getString(1), row.getString(2)});
        for (var candidate : candidates) {
            transactions.run(() -> {
                flows.lock(candidate[0]);
                jdbc.queryForList("""
                        SELECT operation_id FROM auth_direct_operation_receipts WHERE flow_id = ?
                        AND operation_kind = 'LOGOUT' AND operation_id = ? FOR UPDATE
                        """, String.class, candidate[0], candidate[1]);
                jdbc.update("""
                        UPDATE auth_direct_operation_receipts SET status = 'TOMBSTONE',
                        original_flow_cookie_hmac = NULL, original_flow_cookie_generation = NULL,
                        original_refresh_token_hmac = NULL, encrypted_result_response_body = NULL,
                        encrypted_result_refresh_token = NULL, encrypted_result_flow_cookie = NULL,
                        result_session_sid = NULL, result_epoch = NULL, result_revision = NULL,
                        result_flow_cookie_generation = NULL, result_refresh_generation = NULL,
                        expired_cookie_names = NULL, client_expires_at_ms = NULL, effective_expires_at_ms = NULL
                        WHERE flow_id = ? AND operation_kind = 'LOGOUT' AND operation_id = ?
                        AND receipt_expires_at <= CURRENT_TIMESTAMP(6)
                        """, candidate[0], candidate[1]);
                int purged = jdbc.update("""
                        DELETE FROM auth_direct_operation_receipts WHERE flow_id = ? AND operation_kind = 'LOGOUT'
                        AND operation_id = ? AND tombstone_purge_at <= CURRENT_TIMESTAMP(6)
                        """, candidate[0], candidate[1]);
                if (purged == 1) {
                    jdbc.update("""
                            UPDATE auth_browser_flows SET current_direct_operation_kind = NULL,
                            current_direct_operation_id = NULL WHERE flow_id = ?
                            AND current_direct_operation_kind = 'LOGOUT' AND current_direct_operation_id = ?
                            """, candidate[0], candidate[1]);
                }
                return null;
            });
        }
    }
    private void cleanupProjection() {
        var candidates = jdbc.query("""
                SELECT f.flow_id, f.sid FROM auth_direct_session_fences f
                WHERE f.retained_until <= CURRENT_TIMESTAMP(6)
                AND NOT EXISTS (SELECT 1 FROM auth_direct_projection_outbox o WHERE o.sid = f.sid
                    AND (o.processed_at IS NULL OR o.created_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 86400
                    SECOND)))
                AND NOT EXISTS (SELECT 1 FROM auth_browser_sessions s WHERE s.sid = f.sid
                    AND s.status = 'ACTIVE' AND s.absolute_expires_at > CURRENT_TIMESTAMP(6))
                ORDER BY f.retained_until, f.sid LIMIT 20
                """, (row, index) -> new String[]{row.getString(1), row.getString(2)});
        for (var candidate : candidates) {
            transactions.run(() -> {
                flows.lock(candidate[0]);
                jdbc.queryForList("SELECT sid FROM auth_browser_sessions WHERE sid = ? FOR UPDATE",
                        String.class, candidate[1]);
                jdbc.queryForList("SELECT sid FROM auth_direct_session_fences WHERE sid = ? FOR UPDATE",
                        String.class, candidate[1]);
                jdbc.update("""
                        DELETE FROM auth_direct_projection_outbox WHERE sid = ? AND processed_at IS NOT NULL
                        AND created_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 86400 SECOND)
                        """, candidate[1]);
                jdbc.update("""
                        DELETE f FROM auth_direct_session_fences f WHERE sid = ?
                        AND retained_until <= CURRENT_TIMESTAMP(6)
                        AND NOT EXISTS (SELECT 1 FROM auth_direct_projection_outbox o WHERE o.sid = f.sid)
                        AND NOT EXISTS (SELECT 1 FROM auth_browser_sessions s WHERE s.sid = f.sid
                            AND s.status = 'ACTIVE' AND s.absolute_expires_at > CURRENT_TIMESTAMP(6))
                        """, candidate[1]);
                return null;
            });
        }
    }

    private void cleanupRebootstrap() {
        var candidates = jdbc.query("""
                SELECT flow_id, operation_id FROM auth_flow_rebootstrap_operations
                WHERE receipt_expires_at <= CURRENT_TIMESTAMP(6) AND status <> 'TOMBSTONE'
                OR tombstone_purge_at <= CURRENT_TIMESTAMP(6) ORDER BY flow_id, operation_id LIMIT 20
                """, (row, index) -> new String[]{row.getString(1), row.getString(2)});
        for (var candidate : candidates) {
            transactions.run(() -> {
                flows.lock(candidate[0]);
                jdbc.queryForList("""
                        SELECT operation_id FROM auth_flow_rebootstrap_operations WHERE flow_id = ?
                        AND operation_id = ? FOR UPDATE
                        """, String.class, candidate[0], candidate[1]);
                jdbc.update("""
                        UPDATE auth_flow_rebootstrap_operations SET status = 'TOMBSTONE', binding_generation = NULL,
                        result_epoch = NULL, result_revision = NULL, result_flow_cookie_generation = NULL,
                        result_flow_cookie_ciphertext = NULL WHERE flow_id = ? AND operation_id = ?
                        AND receipt_expires_at <= CURRENT_TIMESTAMP(6)
                        """, candidate[0], candidate[1]);
                jdbc.update("""
                        DELETE FROM auth_flow_rebootstrap_operations WHERE flow_id = ? AND operation_id = ?
                        AND tombstone_purge_at <= CURRENT_TIMESTAMP(6)
                        """, candidate[0], candidate[1]);
                return null;
            });
        }
    }

}
