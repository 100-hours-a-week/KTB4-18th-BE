package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthRefreshTest {
    private DirectAuthLoginService.Result login(DirectAuthRuntimeFixture f) {
        var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                UUID.randomUUID().toString());
        return f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                new LoginRequest("test@example.invalid", "test-only-password"),
                new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                System.currentTimeMillis() + 600_000);
    }

    @Test
    void rotatesAtomicallyAndReplaysOnlyOriginalOrExactCanonicalResultProof() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var login = login(f);
            var original = DirectAuthLoginLogoutTest.request(login.cookies());
            var tuple = new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString());
            long expiry = System.currentTimeMillis() + 600_000;
            var result = f.refresh.refresh(original, tuple, expiry);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.flow().epoch()).isEqualTo(3);
            assertThat(f.sessions.require(result.flow(), f.flows.now(), false).refreshGeneration()).isEqualTo(2);
            assertThat(f.refresh.refresh(original, tuple, expiry).data()).isEqualTo(result.data());
            var current = DirectAuthLoginLogoutTest.request(result.cookies());
            assertThat(f.refresh.refresh(current, tuple, expiry).cookies()).isEqualTo(result.cookies());
            assertThatThrownBy(() -> f.refresh.refresh(current, tuple, expiry + 1))
                    .isInstanceOf(DirectAuthFailure.class).hasMessage("AUTH_OPERATION_ID_REUSED");
            var summary = f.recovery.current(current);
            assertThat(((Map<?, ?>) summary.data().get("operation")).get("kind")).isEqualTo("REFRESH");
            assertThat(summary.cookies()).isEmpty();
            assertThat(f.flows.find(tuple.flowId()).orElseThrow().epoch()).isEqualTo(3);
            f.logout.logout(current, new Tuple(tuple.flowId(), 3, UUID.randomUUID().toString()));
            assertThatThrownBy(() -> f.refresh.refresh(original, tuple, expiry))
                    .isInstanceOf(DirectAuthFailure.class).hasMessage("AUTH_OPERATION_SUPERSEDED");
        }
    }

    @Test
    void distinctRacingIdsProduceOneWinnerAndDurableCredentialFreeNegativeReceipt() throws Exception {
        var users = DirectAuthLoginLogoutTest.users();
        try (var f = new DirectAuthRuntimeFixture(users)) {
            var login = login(f);
            var original = DirectAuthLoginLogoutTest.request(login.cookies());
            var ready = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var user = users.findActiveUser(42L);
            when(users.findActiveUser(42L)).thenAnswer(call -> {
                ready.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test barrier timed out");
                }
                return user;
            });
            var first = new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString());
            var second = new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString());
            long expiry = System.currentTimeMillis() + 600_000;
            var a = CompletableFuture.supplyAsync(() -> f.refresh.refresh(original, first, expiry));
            var b = CompletableFuture.supplyAsync(() -> f.refresh.refresh(original, second, expiry));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(DirectAuthRefreshService.Result::status)).containsExactlyInAnyOrder(200,
                    409);
            var rejected = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
            assertThat(rejected.message()).isEqualTo("AUTH_REFRESH_STALE");
            assertThat(rejected.data()).isNull();
            assertThat(rejected.cookies()).isEmpty();
            assertThat(f.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM auth_direct_operation_receipts WHERE operation_kind = 'REFRESH'
                    AND status = 'NEGATIVE_STALE' AND original_flow_cookie_hmac IS NULL
                    AND original_refresh_token_hmac IS NULL AND encrypted_result_response_body IS NULL
                    AND result_session_sid IS NULL
                    """, Integer.class)).isEqualTo(1);
            assertThat(f.flows.find(first.flowId()).orElseThrow().epoch()).isEqualTo(3);
            assertThat(f.jdbc.queryForObject("SELECT refresh_generation FROM auth_browser_sessions", Long.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void missingNegativeCommitNeverReturnsStaleAndDatabaseDeadlineCannotReplay() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var login = login(f);
            var original = DirectAuthLoginLogoutTest.request(login.cookies());
            var first = new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString());
            long expiry = System.currentTimeMillis() + 600_000;
            var result = f.refresh.refresh(original, first, expiry);
            String rejectedId = UUID.randomUUID().toString();
            f.jdbc.execute("""
                    CREATE TRIGGER direct_negative_failure BEFORE INSERT ON auth_direct_operation_receipts
                    FOR EACH ROW BEGIN IF NEW.status = 'NEGATIVE_STALE' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test-only negative write failure'; END IF; END
                    """);
            assertThatThrownBy(() -> f.refresh.refresh(original, new Tuple(first.flowId(), 2, rejectedId), expiry))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(
                    f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_operation_receipts WHERE operation_id = ?",
                            Integer.class, rejectedId))
                    .isZero();
            f.jdbc.update("""
                    UPDATE auth_direct_operation_receipts
                    SET effective_expires_at_ms = 1 WHERE operation_kind = 'REFRESH'
                    """);
            assertThatThrownBy(
                    () -> f.refresh.refresh(DirectAuthLoginLogoutTest.request(result.cookies()), first, expiry))
                    .isInstanceOf(DirectAuthFailure.class).hasMessage("AUTH_OPERATION_RECEIPT_EXPIRED");
        }
    }
}
