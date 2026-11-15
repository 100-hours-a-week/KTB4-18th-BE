package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

class DirectAuthReplayCipherTicketTest {
    private ReplayContext context() {
        return new ReplayContext("bootstrap-exact-response", "auth_flow_bootstrap_operations", "BOOTSTRAP",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "", "", "1", "1", "1", "", "", "1",
                "a".repeat(64), "b".repeat(64));
    }

    @Test
    void ticketsUseDistinctSingleUseIvsOnlyInAuthorizedTransactionAndReplayDoesNotConsume() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var ticket = f.cipher.reserveV2(2, () -> {
            });
            var context = context();
            assertThatThrownBy(() -> f.cipher.encryptV2("test", context))
                    .isInstanceOf(AuthStateUnavailableException.class);
            assertThatThrownBy(() -> f.cipher.encryptV2("test", context, ticket)).isInstanceOf(DirectAuthFailure.class);
            var encrypted = f.transactions.run(() -> {
                f.projections.lockTicket(ticket);
                String a = f.cipher.encryptV2("test", context, ticket);
                String b = f.cipher.encryptV2("test", context, ticket);
                assertThat(a.equals(b)).isFalse();
                assertThatThrownBy(() -> f.cipher.encryptV2("test", context, ticket))
                        .isInstanceOf(DirectAuthFailure.class);
                return a;
            });
            byte[] before = f.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class);
            assertThat(f.cipher.decryptV2(encrypted, context)).isEqualTo("test");
            byte[] after = f.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class);
            assertThat(java.util.Arrays.equals(before, after)).isTrue();
            assertThatThrownBy(() -> f.transactions.run(() -> {
                f.projections.lockTicket(ticket);
                return null;
            })).isInstanceOf(DirectAuthFailure.class);
            var other = f.cipher.reserveV2(1, () -> {
            });
            assertThatThrownBy(() -> f.transactions.run(() -> {
                other.authorize(1, "c".repeat(64));
                return null;
            })).isInstanceOf(DirectAuthFailure.class);
            assertThatThrownBy(() -> f.transactions.run(() -> f.cipher.reserveV2(1, () -> {
            })))
                    .isInstanceOf(AuthStateUnavailableException.class);
            assertThatThrownBy(() -> new com.muse.meomuneum.auth.directv2.model.DirectAuthNonceTicket("k", 1,
                    List.of(new byte[12], new byte[12]))).isInstanceOf(DirectAuthFailure.class);
        }
    }

    @Test
    void versionedCipherBindsAuthenticatedContextAndRejectsTampering() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var context = new ReplayContext("direct-operation-flow-cookie", "auth_direct_operation_receipts", "REFRESH",
                    "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "", "1",
                    "1", "1", "2", "2", "", "", "", "");
            var ticket = f.cipher.reserveV2(1, () -> {
            });
            String encrypted = f.transactions.run(() -> {
                f.projections.lockTicket(ticket);
                return f.cipher.encryptV2("payload", context, ticket);
            });
            assertThat(f.cipher.decryptV2(encrypted, context)).isEqualTo("payload");
            var changedContext = new ReplayContext("operation-response-body", "auth_direct_operation_receipts",
                    "REFRESH", "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000002", "", "1", "1", "1", "2", "2", "", "", "", "");
            assertThatThrownBy(() -> f.cipher.decryptV2(encrypted, changedContext))
                    .isInstanceOf(AuthStateUnavailableException.class);
            byte[] bytes = Base64.getUrlDecoder().decode(encrypted.substring("rrc:v2:".length()));
            bytes[bytes.length - 1] ^= 1;
            String tampered = "rrc:v2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            assertThatThrownBy(() -> f.cipher.decryptV2(tampered, context))
                    .isInstanceOf(AuthStateUnavailableException.class);
        }
    }
}
