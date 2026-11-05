package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthOperationController;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthResponseWriter;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceAdmissionGuard;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthNonceAdmissionMySqlTest {
    @RestController
    static class Bridge {
        private final DirectAuthOperationController controller;

        Bridge(DirectAuthRuntimeFixture f) {
            controller = new DirectAuthOperationController(new DirectAuthRequestParser(),
                    new DirectAuthResponseWriter(),
                    f.flowService, f.login, f.logout, f.recovery, f.refresh, f.rebootstrap);
        }

        @PostMapping("/api/v1/auth/flows")
        ResponseEntity<?> bootstrap(HttpServletRequest request) {
            return controller.bootstrap(request);
        }
    }

    @Test
    void invalidProofParallelRoutesNeverAdvanceAllocatorOrCreateReceiptsAndLaterValidRequestSucceeds()
            throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            byte[] before = next(f);
            try (var pool = Executors.newFixedThreadPool(32)) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 128; index++) {
                    int route = index % 4;
                    futures.add(pool.submit(() -> {
                        var invalid = DirectAuthLoginLogoutTest.request(List.of());
                        assertThatThrownBy(() -> {
                            switch (route) {
                                case 0 -> f.logout.logout(invalid, tuple);
                                case 1 -> f.refresh.refresh(invalid, tuple, System.currentTimeMillis() + 400_000);
                                case 2 -> f.rebootstrap.rebootstrap(invalid, tuple);
                                default -> f.sourceRecovery.recover(invalid, tuple, UUID.randomUUID().toString(),
                                        System.currentTimeMillis() + 400_000);
                            }
                        }).isInstanceOf(DirectAuthFailure.class);
                    }));
                }
                for (var future : futures) {
                    future.get(20, TimeUnit.SECONDS);
                }
            }
            assertThat(Arrays.equals(before, next(f))).isTrue();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_operation_receipts", Integer.class))
                    .isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_recovery_operations", Integer.class))
                    .isZero();
            assertThat(f.logout.logout(DirectAuthLoginLogoutTest.request(bootstrap.cookies()), tuple).flow().epoch())
                    .isEqualTo(2);
        }
    }

    @Test
    void exhaustedGlobalBucketReturnsHttp503WithoutMutationAndKeepsExistingRecoveryOwnerLease() throws Exception {
        var guard = new DirectAuthNonceAdmissionGuard(() -> 0);
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users(), guard)) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"),
                    new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                    System.currentTimeMillis() + 600_000);
            var source = new Tuple(bootstrap.flow().flowId(), 2, UUID.randomUUID().toString());
            f.refresh.refresh(DirectAuthLoginLogoutTest.request(login.cookies()), source,
                    System.currentTimeMillis() + 600_000);
            var proof = new ArrayList<>(login.cookies());
            proof.addAll(bootstrap.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).toList());
            var request = DirectAuthLoginLogoutTest.request(proof);
            String id = UUID.randomUUID().toString();
            long client = System.currentTimeMillis() + 400_000;
            byte[] owner = new byte[16];
            f.transactions.run(() -> {
                f.projections.lockControl();
                return f.sourceLifecycle.claim(request, source, id, client, owner);
            });
            var prior = f.recoveryRows.find(source.flowId(), id, false).orElseThrow();
            while (true) {
                try (var permit = guard.acquire()) {
                    assertThat(permit).isNotNull();
                } catch (DirectAuthFailure exhausted) {
                    break;
                }
            }
            byte[] before = next(f);
            var mvc = MockMvcBuilders.standaloneSetup(new Bridge(f)).build();
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/auth/flows").header("X-Auth-Flow-Operation-Id", UUID.randomUUID().toString())
                    .header("Origin", "http://localhost:5173")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
            assertThat(response.getContentAsString()).contains("AUTH_STATE_UNAVAILABLE").doesNotContain("access_token");
            assertThatThrownBy(() -> f.sourceRecovery.recover(request, source, id, client))
                    .isInstanceOf(DirectAuthFailure.class).hasMessage("AUTH_STATE_UNAVAILABLE");
            var after = f.recoveryRows.find(source.flowId(), id, false).orElseThrow();
            assertThat(Arrays.equals(prior.owner(), after.owner())).isTrue();
            assertThat(after.lease()).isEqualTo(prior.lease());
            assertThat(after.status()).isEqualTo("IN_PROGRESS");
            assertThat(Arrays.equals(before, next(f))).isTrue();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_flow_bootstrap_operations", Integer.class))
                    .isEqualTo(1);
            var held = new ArrayList<DirectAuthNonceAdmissionGuard.Permit>();
            var second = new DirectAuthNonceAdmissionGuard(() -> 0);
            for (int index = 0; index < 32; index++) {
                held.add(second.acquire());
            }
            assertThatThrownBy(second::acquire).isInstanceOf(DirectAuthFailure.class);
            held.forEach(DirectAuthNonceAdmissionGuard.Permit::close);
        }
    }

    private byte[] next(DirectAuthRuntimeFixture f) {
        return f.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class);
    }
}
