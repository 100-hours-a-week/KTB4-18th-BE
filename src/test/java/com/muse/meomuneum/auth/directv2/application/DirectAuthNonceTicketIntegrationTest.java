package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthNonceTicketIntegrationTest {
    private long position(DirectAuthRuntimeFixture f) {
        return new BigInteger(1, f.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class))
                .longValueExact();
    }

    @Test
    void fiveRoutesReserveTheirBudgetsBeforeBusinessTransactionsAndPreserveReplay() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            long before = position(f);
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            assertThat(position(f) - before).isEqualTo(1);
            var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"),
                    new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                    System.currentTimeMillis() + 600_000);
            before = position(f);
            var binding = bootstrap.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).toList();
            var rebootTuple = new Tuple(bootstrap.flow().flowId(), 2, UUID.randomUUID().toString());
            var reboot = f.rebootstrap.rebootstrap(DirectAuthLoginLogoutTest.request(binding), rebootTuple);
            assertThat(position(f) - before).isEqualTo(1);
            var proof = new ArrayList<>(login.cookies().stream().filter(cookie -> cookie.startsWith("refresh_token"))
                    .toList());
            proof.addAll(reboot.cookies());
            before = position(f);
            var tuple = new Tuple(bootstrap.flow().flowId(), 2, UUID.randomUUID().toString());
            long client = System.currentTimeMillis() + 600_000;
            var refresh = f.refresh.refresh(DirectAuthLoginLogoutTest.request(proof), tuple, client);
            assertThat(position(f) - before).isEqualTo(3);
            assertThat(f.refresh.refresh(DirectAuthLoginLogoutTest.request(proof), tuple, client).data())
                    .isEqualTo(refresh.data());
            proof.addAll(binding);
            before = position(f);
            var recovered = f.sourceRecovery.recover(DirectAuthLoginLogoutTest.request(proof), tuple,
                    UUID.randomUUID().toString(), System.currentTimeMillis() + 400_000);
            assertThat(position(f) - before).isEqualTo(3);
            before = position(f);
            f.logout.logout(DirectAuthLoginLogoutTest.request(recovered.cookies()),
                    new Tuple(tuple.flowId(), recovered.flow().epoch(), UUID.randomUUID().toString()));
            assertThat(position(f) - before).isEqualTo(2);
        }
    }

    @Test
    void controlUpdateBeforeReservationLetsRebootstrapCompleteWithoutNestedLockWait() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users());
                var pool = Executors.newFixedThreadPool(2)) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var update = pool.submit(() -> f.transactions.run(() -> {
                f.jdbc.update("UPDATE auth_direct_projection_control SET projection_generation = 2");
                locked.countDown();
                await(release);
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var request = pool.submit(() -> f.rebootstrap.rebootstrap(
                    DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString())));
            release.countDown();
            update.get(5, TimeUnit.SECONDS);
            assertThat(request.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
    }

    @Test
    void staleTicketFailsAndMainControlLockOrdersGenerationUpdateAfterCommit() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users());
                var pool = Executors.newSingleThreadExecutor()) {
            var ticket = f.cipher.reserveV2(1, () -> {
            });
            f.jdbc.update("UPDATE auth_direct_projection_control SET projection_generation = 2");
            assertThatThrownBy(() -> f.transactions.run(() -> f.projections.lockTicket(ticket)))
                    .isInstanceOf(DirectAuthFailure.class);
            var current = f.cipher.reserveV2(1, () -> {
            });
            var started = new CountDownLatch(1);
            var update = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            f.transactions.run(() -> {
                f.projections.lockTicket(current);
                update.set(pool.submit(() -> {
                    started.countDown();
                    f.jdbc.update("UPDATE auth_direct_projection_control SET projection_generation = 3");
                }));
                await(started);
                assertThatThrownBy(() -> update.get().get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                current.consume(current.keyId());
                return null;
            });
            update.get().get(5, TimeUnit.SECONDS);
            var changedKey = f.cipher.reserveV2(1, () -> {
            });
            f.jdbc.update("UPDATE auth_replay_key_pin SET key_id = ?", "c".repeat(64));
            assertThatThrownBy(() -> f.transactions.run(() -> f.projections.lockTicket(changedKey)))
                    .isInstanceOf(DirectAuthFailure.class);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier timeout");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
