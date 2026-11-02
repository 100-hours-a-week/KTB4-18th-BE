package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthCleanupTest {
    private DirectAuthLoginService.Result login(DirectAuthRuntimeFixture f) {
        var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                UUID.randomUUID().toString());
        return f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                new LoginRequest("test@example.invalid", "test-only-password"),
                new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                System.currentTimeMillis() + 600_000);
    }

    @Test
    void projectionCleanupRetainsPendingEventsThenRemovesExpiredFenceAndFlowRestriction() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var login = login(f);
            f.logout.logout(DirectAuthLoginLogoutTest.request(login.cookies()),
                    new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString()));
            f.jdbc.update("""
                    UPDATE auth_browser_sessions SET absolute_expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL
                    2 DAY)
                    """);
            f.jdbc.update("""
                    UPDATE auth_direct_session_fences SET retained_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL
                    1 DAY)
                    """);
            f.jdbc.update("""
                    UPDATE auth_direct_projection_outbox SET created_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY)
                    """);
            var cleanup = new DirectAuthReceiptCleanupService(f.jdbc, f.transactions, f.flows, f.operations,
                    f.lifecycle);
            cleanup.clean();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_session_fences", Integer.class))
                    .isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_projection_outbox", Integer.class))
                    .isEqualTo(2);
            f.jdbc.update("UPDATE auth_direct_projection_outbox SET processed_at = CURRENT_TIMESTAMP(6)");
            cleanup.clean();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_projection_outbox", Integer.class))
                    .isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_session_fences", Integer.class))
                    .isZero();
            assertThat(f.jdbc.update("DELETE FROM auth_browser_flows WHERE flow_id = ?", login.flow().flowId()))
                    .isEqualTo(1);
        }
    }

    @Test
    void refreshSecretsScrubAtCutoffAndRecoveryChildPurgesBeforeRestrictSource() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var login = login(f);
            var tuple = new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString());
            long expiry = System.currentTimeMillis() + 600_000;
            var refresh = f.refresh.refresh(DirectAuthLoginLogoutTest.request(login.cookies()), tuple, expiry);
            f.jdbc.update("""
                    UPDATE auth_direct_operation_receipts
                    SET effective_expires_at_ms = 1 WHERE operation_kind = 'REFRESH'
                    """);
            var cleanup = new DirectAuthRefreshReceiptCleanupService(f.jdbc, f.transactions, f.flows);
            cleanup.clean();
            assertThat(f.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM auth_direct_operation_receipts WHERE operation_kind = 'REFRESH'
                    AND status = 'TOMBSTONE' AND encrypted_result_response_body IS NULL
                    AND encrypted_result_refresh_token IS NULL AND encrypted_result_flow_cookie IS NULL
                    AND original_flow_cookie_hmac IS NULL AND original_refresh_token_hmac IS NULL
                    AND client_expires_at_ms IS NULL AND effective_expires_at_ms IS NULL
                    """, Integer.class)).isEqualTo(1);
            assertThatThrownBy(
                    () -> f.refresh.refresh(DirectAuthLoginLogoutTest.request(refresh.cookies()), tuple, expiry))
                    .hasMessage("AUTH_OPERATION_RECEIPT_EXPIRED");
            String recoveryId = UUID.randomUUID().toString();
            f.jdbc.update("""
                    INSERT INTO auth_refresh_recovery_operations
                    (flow_id, recovery_operation_id, source_refresh_operation_id, starting_epoch, status,
                     receipt_expires_at, tombstone_purge_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'TOMBSTONE', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND),
                     DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, tuple.flowId(), recoveryId, tuple.operationId(), tuple.epoch());
            f.jdbc.update("""
                    UPDATE auth_direct_operation_receipts
                    SET tombstone_purge_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                    """);
            cleanup.clean();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_operation_receipts", Integer.class))
                    .isEqualTo(1);
            f.jdbc.update("""
                    UPDATE auth_refresh_recovery_operations
                    SET tombstone_purge_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                    """);
            cleanup.clean();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_recovery_operations", Integer.class))
                    .isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_operation_receipts", Integer.class))
                    .isZero();
        }
    }
    @Test
    void blockedTwentyCandidatesCannotStarveLaterPurgeableFenceOrRefreshReceipt() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            String flow = bootstrap.flow().flowId();
            for (int index = 0; index < 21; index++) {
                String id = String.format("00000000-0000-4000-8000-%012d", index);
                f.jdbc.update("""
                        INSERT INTO auth_direct_session_fences
                        (sid, flow_id, flow_epoch, revision, projection_generation, status, updated_at, retained_until)
                        VALUES (?, ?, 1, 1, 1, 'REVOKED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY),
                            DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))
                        """, id, flow);
                f.jdbc.update("""
                        INSERT INTO auth_direct_operation_receipts
                        (flow_id, operation_kind, operation_id, starting_epoch, status, receipt_expires_at,
                         tombstone_purge_at, created_at)
                        VALUES (?, 'REFRESH', ?, 1, 'TOMBSTONE', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY),
                            DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY), DATE_SUB(CURRENT_TIMESTAMP(6),
                            INTERVAL 2 DAY))
                        """, flow, id);
                if (index < 20) {
                    f.jdbc.update(
                            "INSERT INTO auth_direct_projection_outbox (flow_id, sid, created_at) "
                                    + "VALUES (?, ?, CURRENT_TIMESTAMP(6))",
                            flow, id);
                    f.jdbc.update("""
                            INSERT INTO auth_refresh_recovery_operations
                            (flow_id, recovery_operation_id, source_refresh_operation_id, starting_epoch, status,
                             receipt_expires_at, tombstone_purge_at, created_at, updated_at)
                            VALUES (?, ?, ?, 1, 'TOMBSTONE', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND),
                                DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY), CURRENT_TIMESTAMP(6),
                                CURRENT_TIMESTAMP(6))
                            """, flow, UUID.randomUUID().toString(), id);
                }
            }
            new DirectAuthReceiptCleanupService(f.jdbc, f.transactions, f.flows, f.operations, f.lifecycle).clean();
            new DirectAuthRefreshReceiptCleanupService(f.jdbc, f.transactions, f.flows).clean();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_session_fences", Integer.class))
                    .isEqualTo(20);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_operation_receipts", Integer.class))
                    .isEqualTo(20);
        }
    }

}
