package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthRebootstrapTest {
    @Test
    void explicitBindingRebootstrapReturnsOnlyFlowCookieAndExactRetryNeverRotatesTwice() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var request = DirectAuthLoginLogoutTest.request(bootstrap.cookies().stream()
                    .filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).toList());
            assertThatThrownBy(() -> f.flowService.current(request)).hasMessage("AUTH_FLOW_REBOOTSTRAP_REQUIRED");
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            var result = f.rebootstrap.rebootstrap(request, tuple);
            assertThat(result.flow().epoch()).isEqualTo(1);
            assertThat(result.flow().flowCookieGeneration()).isEqualTo(2);
            assertThat(result.cookies()).singleElement().asString().startsWith("MEO_AUTH_FLOW_V2_")
                    .doesNotContain("refresh_token", "MEO_AUTH_BINDING");
            assertThat(result.data()).doesNotContainKey("access_token");
            assertThat(f.rebootstrap.rebootstrap(request, tuple).cookies()).isEqualTo(result.cookies());
            assertThat(f.flows.find(tuple.flowId()).orElseThrow().flowCookieGeneration()).isEqualTo(2);
            assertThat(f.flowService.current(DirectAuthLoginLogoutTest.request(result.cookies())).flow().lifecycle())
                    .isEqualTo("ANONYMOUS");
            assertThat(result.flow().cookieExpiresAt()).isEqualTo(bootstrap.flow().cookieExpiresAt());
        }
    }

    @Test
    void rebootstrapWinsAgainstRunningPasswordWorkerWithoutCreatingAnySession() throws Exception {
        var users = DirectAuthLoginLogoutTest.users();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var user = users.findActiveUser(42L);
        when(users.authenticate("test@example.invalid", "test-only-password")).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier timed out");
            }
            return user;
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
            var result = f.rebootstrap.rebootstrap(request,
                    new Tuple(loginTuple.flowId(), 1, UUID.randomUUID().toString()));
            assertThat(result.flow().epoch()).isEqualTo(2);
            assertThat(result.flow().lifecycle()).isEqualTo("ANONYMOUS");
            assertThat(f.jdbc.queryForObject("SELECT lifecycle FROM auth_browser_flows WHERE flow_id = ?",
                    String.class, loginTuple.flowId())).isEqualTo("ANONYMOUS");
            assertThat(f.flowService.current(DirectAuthLoginLogoutTest.request(result.cookies())).flow().lifecycle())
                    .isEqualTo("ANONYMOUS");
            release.countDown();
            assertThatThrownBy(() -> worker.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DirectAuthFailure.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions", Integer.class)).isZero();
            assertThat(f.jdbc.queryForObject("SELECT owner_token FROM auth_login_operations", byte[].class)).isNull();
        } finally {
            release.countDown();
        }
    }

    @Test
    void sessionCommittedFirstSurvivesAndLoginRecoveryUsesProvenCurrentRebootstrapResult() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var request = DirectAuthLoginLogoutTest.request(bootstrap.cookies());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            var login = f.login.login(request, new LoginRequest("test@example.invalid", "test-only-password"), tuple,
                    System.currentTimeMillis() + 600_000);
            var restored = f.rebootstrap.rebootstrap(request,
                    new Tuple(tuple.flowId(), 1, UUID.randomUUID().toString()));
            assertThat(restored.flow().epoch()).isEqualTo(2);
            assertThat(restored.flow().activeSessionSid()).isEqualTo(login.flow().activeSessionSid());
            var resultProof = DirectAuthLoginLogoutTest.request(restored.cookies());
            assertThat(f.recovery.recoverLogin(resultProof, tuple).status()).isEqualTo(200);
            assertThat(((java.util.Map<?, ?>) f.recovery.current(resultProof).data().get("operation"))
                    .get("resultRevision")).isEqualTo(Long.toString(restored.flow().revision()));
            assertThat(((java.util.Map<?, ?>) f.recovery.status(resultProof, tuple).data()).get("resultRevision"))
                    .isEqualTo(Long.toString(restored.flow().revision()));
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions", Integer.class)).isEqualTo(1);
        }
    }
}
