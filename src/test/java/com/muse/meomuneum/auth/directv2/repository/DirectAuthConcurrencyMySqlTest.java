package com.muse.meomuneum.auth.directv2.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthConcurrencyMySqlTest {
    @Test
    void logoutWinsWhilePasswordWorkerIsOutsideDatabaseLocksAndLateWorkerCreatesNothing() throws Exception {
        var users = DirectAuthLoginLogoutTest.users();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var originalUser = users.findActiveUser(42L);
        when(users.authenticate("test@example.invalid", "test-only-password")).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test barrier timed out");
            }
            return originalUser;
        });
        try (var f = new DirectAuthRuntimeFixture(users)) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var request = DirectAuthLoginLogoutTest.request(bootstrap.cookies());
            var loginTuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            var worker = CompletableFuture.supplyAsync(() -> f.login.login(request,
                    new LoginRequest("test@example.invalid", "test-only-password"), loginTuple,
                    System.currentTimeMillis() + 600_000));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var logoutTuple = new Tuple(loginTuple.flowId(), 1, UUID.randomUUID().toString());
            var result = f.logout.logout(request, logoutTuple);
            assertThat(result.flow().epoch()).isEqualTo(2);
            assertThat(result.flow().lifecycle()).isEqualTo("ANONYMOUS");
            release.countDown();
            assertThatThrownBy(() -> worker.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DirectAuthFailure.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions", Integer.class)).isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_session_fences", Integer.class))
                    .isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_projection_outbox", Integer.class))
                    .isZero();
            assertThat(f.jdbc.queryForObject("SELECT status FROM auth_login_operations", String.class))
                    .isEqualTo("SUPERSEDED");
        } finally {
            release.countDown();
        }
    }

    @Test
    void expiredLeaseIsRecoveredBeforeNewClaimAndOldOwnerCannotFinalize() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            String flowId = bootstrap.flow().flowId();
            var tuple = new Tuple(flowId, 1, UUID.randomUUID().toString());
            byte[] owner = new byte[16];
            long client = System.currentTimeMillis() + 600_000;
            f.transactions.run(() -> {
                var flow = f.flows.lock(flowId).orElseThrow();
                var proof = f.cookies.require(DirectAuthLoginLogoutTest.request(bootstrap.cookies()), flow,
                        f.flows.now());
                f.lifecycle.claim(flow, tuple, client, f.lifecycle.effective(client, f.flows.now()), owner,
                        f.cookies.hmac("direct-login-original:" + proof.value()));
                return null;
            });
            f.jdbc.update(
                    "UPDATE auth_login_operations SET lease_expires_at = "
                            + "DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)");
            f.transactions.run(() -> {
                var flow = f.flows.lock(flowId).orElseThrow();
                assertThat(f.lifecycle.recoverExpired(flow, f.flows.now())).isTrue();
                assertThat(f.operations.lockOwnedLogin(flowId, tuple.operationId(), 1, 1, owner)).isEmpty();
                return null;
            });
            assertThat(f.jdbc.queryForObject("SELECT owner_token FROM auth_login_operations", byte[].class)).isNull();
            assertThat(f.flows.find(flowId).orElseThrow().epoch()).isEqualTo(1);
            assertThat(f.flows.find(flowId).orElseThrow().activeLoginOperationId()).isNull();
        }
    }

    @Test
    void finalizationZeroRowRollsBackSessionAndReceipt() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            String id = bootstrap.flow().flowId();
            var tuple = new Tuple(id, 1, UUID.randomUUID().toString());
            byte[] owner = new byte[16];
            f.transactions.run(() -> {
                var flow = f.flows.lock(id).orElseThrow();
                f.lifecycle.claim(flow, tuple, System.currentTimeMillis() + 600_000,
                        System.currentTimeMillis() + 600_000, owner, new byte[32]);
                return null;
            });
            assertThatThrownBy(() -> f.transactions.run(() -> {
                var flow = f.flows.lock(id).orElseThrow();
                var operation = f.operations.lockLogin(id, tuple.operationId()).orElseThrow();
                f.jdbc.update("UPDATE auth_login_operations SET owner_token = ? WHERE flow_id = ?", new byte[]{1}, id);
                f.lifecycle.complete(flow, operation, owner, "SUCCEEDED", 2, 3, "never-created", 2);
                return null;
            })).isInstanceOf(DirectAuthFailure.class);
            assertThat(f.jdbc.queryForObject("SELECT status FROM auth_login_operations", String.class))
                    .isEqualTo("IN_PROGRESS");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions", Integer.class)).isZero();
        }
    }
}
