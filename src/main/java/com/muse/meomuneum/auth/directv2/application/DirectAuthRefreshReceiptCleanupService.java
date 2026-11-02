package com.muse.meomuneum.auth.directv2.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;

@Service
@ConditionalOnProperty(prefix = "auth.direct-v2", name = "enabled", havingValue = "true")
public class DirectAuthRefreshReceiptCleanupService {
    private final JdbcTemplate jdbc;
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;

    public DirectAuthRefreshReceiptCleanupService(JdbcTemplate jdbc, DirectAuthTransactionRunner transactions,
            DirectAuthFlowRepository flows) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.flows = flows;
    }

    @Scheduled(fixedDelayString = "${auth.direct-v2.cleanup-interval:60s}")
    public void clean() {
        try {
            cleanupRecoveries();
            cleanupSources();
        } catch (RuntimeException deferred) {
            return;
        }
    }

    private void cleanupRecoveries() {
        var candidates = jdbc.query("""
                SELECT flow_id, recovery_operation_id, source_refresh_operation_id FROM auth_refresh_recovery_operations
                WHERE receipt_expires_at <= CURRENT_TIMESTAMP(6) AND status <> 'TOMBSTONE'
                OR effective_expires_at_ms IS NOT NULL AND CURRENT_TIMESTAMP(6) >=
                    TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000, CAST('1970-01-01' AS DATETIME(6)))
                OR tombstone_purge_at <= CURRENT_TIMESTAMP(6) ORDER BY flow_id, recovery_operation_id LIMIT 20
                """, (row, index) -> new String[]{row.getString(1), row.getString(2), row.getString(3)});
        for (var candidate : candidates) {
            transactions.run(() -> {
                flows.lock(candidate[0]);
                jdbc.queryForList("""
                        SELECT operation_id FROM auth_direct_operation_receipts WHERE flow_id = ?
                        AND operation_kind = 'REFRESH' AND operation_id = ? FOR UPDATE
                        """, String.class, candidate[0], candidate[2]);
                jdbc.queryForList("""
                        SELECT recovery_operation_id FROM auth_refresh_recovery_operations
                        WHERE flow_id = ? AND recovery_operation_id = ? FOR UPDATE
                        """, String.class, candidate[0], candidate[1]);
                jdbc.update("""
                        UPDATE auth_refresh_recovery_operations SET status = 'TOMBSTONE', owner_token = NULL,
                        lease_expires_at = NULL, binding_generation = NULL, claim_flow_cookie_generation = NULL,
                        claim_refresh_generation = NULL, result_session_sid = NULL, result_epoch = NULL,
                        result_revision = NULL, result_flow_cookie_generation = NULL, result_refresh_generation = NULL,
                        encrypted_result_response_body = NULL, encrypted_result_refresh_cookie = NULL,
                        encrypted_result_flow_cookie = NULL, client_expires_at_ms = NULL, effective_expires_at_ms
                        = NULL,
                        updated_at = CURRENT_TIMESTAMP(6) WHERE flow_id = ? AND recovery_operation_id = ?
                        AND (receipt_expires_at <= CURRENT_TIMESTAMP(6) OR effective_expires_at_ms IS NOT NULL
                        AND CURRENT_TIMESTAMP(6) >= TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000,
                            CAST('1970-01-01' AS DATETIME(6))))
                        """, candidate[0], candidate[1]);
                int purged = jdbc.update("""
                        DELETE FROM auth_refresh_recovery_operations WHERE flow_id = ? AND recovery_operation_id = ?
                        AND tombstone_purge_at <= CURRENT_TIMESTAMP(6)
                        """, candidate[0], candidate[1]);
                if (purged == 1) {
                    jdbc.update("""
                            UPDATE auth_browser_flows SET current_refresh_recovery_operation_id = NULL,
                            current_refresh_recovery_source_operation_id = NULL, current_refresh_recovery_status = NULL,
                            current_refresh_recovery_lease_expires_at = NULL,
                            current_refresh_recovery_claim_flow_cookie_generation = NULL,
                            current_refresh_recovery_claim_refresh_generation = NULL,
                            current_refresh_recovery_result_flow_cookie_generation = NULL,
                            current_refresh_recovery_result_refresh_generation = NULL
                            WHERE flow_id = ? AND current_refresh_recovery_operation_id = ?
                            AND current_refresh_recovery_source_operation_id = ?
                            """, candidate[0], candidate[1], candidate[2]);
                }
                return null;
            });
        }
    }

    private void cleanupSources() {
        var candidates = jdbc.query("""
                SELECT s.flow_id, s.operation_id FROM auth_direct_operation_receipts s WHERE operation_kind = 'REFRESH'
                AND (receipt_expires_at <= CURRENT_TIMESTAMP(6) AND status NOT IN ('TOMBSTONE', 'NEGATIVE_STALE')
                OR effective_expires_at_ms IS NOT NULL AND CURRENT_TIMESTAMP(6) >=
                    TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000, CAST('1970-01-01' AS DATETIME(6)))
                OR tombstone_purge_at <= CURRENT_TIMESTAMP(6) AND NOT EXISTS (
                    SELECT 1 FROM auth_refresh_recovery_operations r WHERE r.flow_id = s.flow_id
                    AND r.source_refresh_operation_id = s.operation_id)) ORDER BY flow_id, operation_id LIMIT 20
                """, (row, index) -> new String[]{row.getString(1), row.getString(2)});
        for (var candidate : candidates) {
            transactions.run(() -> {
                flows.lock(candidate[0]);
                jdbc.queryForList("""
                        SELECT operation_id FROM auth_direct_operation_receipts WHERE flow_id = ?
                        AND operation_kind = 'REFRESH' AND operation_id = ? FOR UPDATE
                        """, String.class, candidate[0], candidate[1]);
                jdbc.update("""
                        UPDATE auth_direct_operation_receipts SET status = 'TOMBSTONE',
                        original_flow_cookie_generation = NULL, original_flow_cookie_hmac = NULL,
                        original_refresh_token_hmac = NULL, result_session_sid = NULL, result_epoch = NULL,
                        result_revision = NULL, result_flow_cookie_generation = NULL, result_refresh_generation = NULL,
                        encrypted_result_response_body = NULL, encrypted_result_flow_cookie = NULL,
                        encrypted_result_refresh_token = NULL, expired_cookie_names = NULL,
                        client_expires_at_ms = NULL, effective_expires_at_ms = NULL
                        WHERE flow_id = ? AND operation_kind = 'REFRESH' AND operation_id = ?
                        AND status <> 'NEGATIVE_STALE' AND (receipt_expires_at <= CURRENT_TIMESTAMP(6)
                        OR effective_expires_at_ms IS NOT NULL AND CURRENT_TIMESTAMP(6) >=
                        TIMESTAMPADD(MICROSECOND, effective_expires_at_ms * 1000, CAST('1970-01-01' AS DATETIME(6))))
                        """, candidate[0], candidate[1]);
                int purged = jdbc.update("""
                        DELETE s FROM auth_direct_operation_receipts s WHERE flow_id = ?
                        AND operation_kind = 'REFRESH' AND operation_id = ? AND tombstone_purge_at <=
                        CURRENT_TIMESTAMP(6)
                        AND NOT EXISTS (SELECT 1 FROM auth_refresh_recovery_operations r WHERE r.flow_id = s.flow_id
                            AND r.source_refresh_operation_id = s.operation_id)
                        """, candidate[0], candidate[1]);
                if (purged == 1) {
                    jdbc.update("""
                            UPDATE auth_browser_flows SET current_direct_operation_kind = NULL,
                            current_direct_operation_id = NULL WHERE flow_id = ? AND current_direct_operation_kind
                            = 'REFRESH'
                            AND current_direct_operation_id = ?
                            """, candidate[0], candidate[1]);
                }
                return null;
            });
        }
    }
}
