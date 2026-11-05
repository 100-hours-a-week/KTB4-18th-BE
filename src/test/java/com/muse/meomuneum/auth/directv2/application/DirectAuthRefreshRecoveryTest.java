package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthRefreshRecoveryTest {
    private record Start(MockHttpServletRequest binding, MockHttpServletRequest proof, Tuple tuple, long expiry,
            DirectAuthRefreshService.Result refresh) {
    }

    private Start start(DirectAuthRuntimeFixture f) {
        var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                UUID.randomUUID().toString());
        var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                new LoginRequest("test@example.invalid", "test-only-password"),
                new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                System.currentTimeMillis() + 600_000);
        var tuple = new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString());
        long expiry = System.currentTimeMillis() + 600_000;
        var refresh = f.refresh.refresh(DirectAuthLoginLogoutTest.request(login.cookies()), tuple, expiry);
        var proofCookies = new ArrayList<String>(login.cookies());
        bootstrap.cookies().stream().filter(value -> value.startsWith("MEO_AUTH_BINDING_V2_"))
                .forEach(proofCookies::add);
        return new Start(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                DirectAuthLoginLogoutTest.request(proofCookies), tuple, expiry, refresh);
    }

    @Test
    void sourceReplayProofRequiredForExplicitRecoveryAndBindingReadsRemainReadonly() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var start = start(f);
            String id = UUID.randomUUID().toString();
            assertThat(f.sourceReads.read(start.binding(), start.tuple(), null).data().get("status")).isEqualTo("NONE");
            long expiry = System.currentTimeMillis() + 800_000;
            assertThatThrownBy(() -> f.sourceRecovery.recover(start.binding(), start.tuple(), id, expiry))
                    .hasMessage("REFRESH_OPERATION_NOT_FOUND");
            assertThat(f.recoveryRows.find(start.tuple().flowId(), id, false)).isEmpty();
            var result = f.sourceRecovery.recover(start.proof(), start.tuple(), id, expiry);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.flow().epoch()).isEqualTo(start.refresh().flow().epoch());
            var row = f.recoveryRows.find(start.tuple().flowId(), id, false).orElseThrow();
            assertThat(row.effective()).isLessThanOrEqualTo(start.expiry());
            assertThat(f.sourceRecovery.recover(start.proof(), start.tuple(), id, expiry).data())
                    .isEqualTo(result.data());
            var exact = f.sourceReads.read(start.binding(), start.tuple(), id);
            assertThat(exact.data().get("status")).isEqualTo("SUCCEEDED");
            assertThat(exact.data()).doesNotContainKey("access_token");
            assertThat(exact.cookies()).isEmpty();
            assertThat(f.sourceReads.read(start.binding(), start.tuple(), null).data().get("recoveryOperationId"))
                    .isEqualTo(id);
            assertThat(f.recoveryRows.find(start.tuple().flowId(), id, false).orElseThrow().effective())
                    .isEqualTo(row.effective());
            assertThatThrownBy(() -> f.sourceRecovery.recover(start.proof(), start.tuple(),
                    UUID.randomUUID().toString(), expiry))
                    .hasMessage("AUTH_RECOVERY_ALREADY_COMPLETED");
            var joined = new ArrayList<String>(result.cookies());
            if (start.binding().getCookies() != null) {
                for (var cookie : start.binding().getCookies()) {
                    if (cookie.getName().startsWith("MEO_AUTH_BINDING")) {
                        joined.add(cookie.getName() + "=" + cookie.getValue());
                    }
                }
            }
            assertThat(f.recovery.current(DirectAuthLoginLogoutTest.request(joined)).data().get("operation"))
                    .isNotNull();
            assertThat(f.sessions.require(result.flow(), f.flows.now(), false).refreshGeneration()).isEqualTo(3);
        }
    }

    @Test
    void expiredGetIsReadonlyAndOnlySameIdPostReclaimsOwnerAndFencesLateWorker() throws Exception {
        var users = DirectAuthLoginLogoutTest.users();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var f = new DirectAuthRuntimeFixture(users)) {
            var start = start(f);
            var user = users.findActiveUser(42L);
            var calls = new AtomicInteger();
            when(users.findActiveUser(42L)).thenAnswer(call -> {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown();
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("barrier timed out");
                    }
                }
                return user;
            });
            String id = UUID.randomUUID().toString();
            long expiry = System.currentTimeMillis() + 500_000;
            var first = CompletableFuture
                    .supplyAsync(() -> f.sourceRecovery.recover(start.proof(), start.tuple(), id, expiry));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var initial = f.recoveryRows.find(start.tuple().flowId(), id, false).orElseThrow();
            f.jdbc.update("""
                    UPDATE auth_refresh_recovery_operations
                    SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                    """);
            f.jdbc.update("""
                    UPDATE auth_browser_flows f JOIN auth_refresh_recovery_operations r ON r.flow_id = f.flow_id
                    SET f.current_refresh_recovery_lease_expires_at = r.lease_expires_at
                    """);
            var expired = f.sourceReads.read(start.binding(), start.tuple(), null);
            assertThat(expired.status()).isEqualTo(409);
            assertThat(expired.message()).isEqualTo("AUTH_RECOVERY_LEASE_EXPIRED");
            assertThat(expired.data().get("recoveryOperationId")).isEqualTo(id);
            assertThat(expired.data().get("clientExpiresAt")).isEqualTo(Long.toString(expiry));
            assertThat(expired.data().get("effectiveExpiresAt")).isEqualTo(initial.effective().toString());
            assertThat(f.recoveryRows.find(start.tuple().flowId(), id, false).orElseThrow().owner())
                    .isEqualTo(initial.owner());
            var second = f.sourceRecovery.recover(start.proof(), start.tuple(), id, expiry);
            assertThat(second.status()).isEqualTo(200);
            release.countDown();
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DirectAuthFailure.class);
            var completed = f.recoveryRows.find(start.tuple().flowId(), id, false).orElseThrow();
            assertThat(completed.effective()).isEqualTo(initial.effective());
            assertThat(completed.owner()).isNull();
            assertThat(f.jdbc.queryForObject("SELECT refresh_generation FROM auth_browser_sessions", Long.class))
                    .isEqualTo(3);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_projection_outbox", Integer.class))
                    .isEqualTo(3);
        } finally {
            release.countDown();
        }
    }
}
