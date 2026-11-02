package com.muse.meomuneum.auth.directv2.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

class DirectAuthNonceAllocatorTest {
    private com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture fixture;

    @org.junit.jupiter.api.AfterEach
    void closeFixture() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void committedReservationsSurviveOuterRollbackAndMultipleInstances() throws Exception {
        fixture = new com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture(
                com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest.users());
        var source = fixture.jdbc.getDataSource();
        var jdbc = new JdbcTemplate(source);

        jdbc.update("DELETE FROM auth_replay_key_pin");
        jdbc.update("DELETE FROM auth_replay_iv_allocators");
        String id = "a".repeat(64);
        jdbc.update("INSERT INTO auth_replay_key_pin VALUES (1, ?)", id);
        jdbc.update("INSERT INTO auth_replay_iv_allocators VALUES (?, ?, FALSE)", id, new byte[12]);
        var manager = new DataSourceTransactionManager(source);
        var approved = new AtomicBoolean(true);
        DirectAuthNonceAllocator.RestoreSafetyGate gate = key -> {
            if (!approved.get()) {
                throw new AuthStateUnavailableException();
            }
        };
        var first = new DirectAuthNonceAllocator(jdbc, manager, gate);
        var second = new DirectAuthNonceAllocator(jdbc, manager, gate);
        var prior = reserve(first, id);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status -> {
            throw new IllegalStateException();
        }));
        assertThrows(AuthStateUnavailableException.class, () -> new TransactionTemplate(manager)
                .execute(status -> first.reserve(id, 1, () -> {
                })));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 40).mapToObj(i -> executor.submit(
                    () -> HexFormat.of().formatHex(reserve(i % 2 == 0 ? first : second, id)))).toList();
            var allocated = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).toList();
            assertEquals(40, allocated.stream().distinct().count());
            assertFalse(allocated.contains("000000000000000000000000"));
        }
        assertEquals("000000000000000000000029", HexFormat.of().formatHex(reserve(first, id)));
        assertThrows(AuthStateUnavailableException.class, () -> reserve(first, "b".repeat(64)));
        var unavailable = new DirectAuthNonceAllocator(jdbc, manager,
                (DirectAuthNonceAllocator.RestoreSafetyGate) null);
        assertThrows(AuthStateUnavailableException.class, () -> reserve(unavailable, id));
        approved.set(false);
        jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ? WHERE key_id = ?", new byte[12], id);
        assertThrows(AuthStateUnavailableException.class, () -> reserve(first, id));
        jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ? WHERE key_id = ?",
                HexFormat.of().parseHex("00000000000000000000002a"), id);
        approved.set(true);
        assertEquals("00000000000000000000002a", HexFormat.of().formatHex(reserve(first, id)));
        jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ?, exhausted = FALSE WHERE key_id = ?",
                HexFormat.of().parseHex("ffffffffffffffffffffffff"), id);
        assertEquals("ffffffffffffffffffffffff", HexFormat.of().formatHex(reserve(first, id)));
        assertThrows(AuthStateUnavailableException.class, () -> reserve(first, id));
    }
    private byte[] reserve(DirectAuthNonceAllocator allocator, String id) {
        var ticket = allocator.reserve(id, 1, () -> {
        });
        return fixture.transactions.run(() -> {
            ticket.authorize(1, id);
            return ticket.consume(id);
        });
    }

    @Test
    void nonceReservationRemainsAfterOuterRollback() throws Exception {
        fixture = new com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture(
                com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest.users());
        var ticket = fixture.cipher.reserveV2(3, () -> {
        });
        byte[] reserved = fixture.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class);
        assertThrows(IllegalStateException.class, () -> fixture.transactions.run(() -> {
            fixture.projections.lockTicket(ticket);
            ticket.consume(ticket.keyId());
            throw new IllegalStateException();
        }));
        assertTrue(java.util.Arrays.equals(reserved,
                fixture.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class)));
        var next = fixture.cipher.reserveV2(1, () -> {
        });
        fixture.transactions.run(() -> {
            fixture.projections.lockTicket(next);
            next.consume(next.keyId());
            return null;
        });
        assertFalse(java.util.Arrays.equals(reserved,
                fixture.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class)));
        assertThrows(com.muse.meomuneum.auth.directv2.model.DirectAuthFailure.class,
                () -> fixture.transactions.run(() -> fixture.projections.lockTicket(ticket)));
    }

    @Test
    void restoreNewKeyPinAllocatorRejectsOldReceiptAndNeverReusesIv() throws Exception {
        fixture = new com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture(
                com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest.users());
        var context = new com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext(
                "bootstrap-exact-response", "auth_flow_bootstrap_operations", "BOOTSTRAP",
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), "", "", "1", "1", "1",
                "", "", "1", "a".repeat(64), "b".repeat(64));
        var old = fixture.cipher.reserveV2(1, () -> {
        });
        String encrypted = fixture.transactions.run(() -> {
            fixture.projections.lockTicket(old);
            return fixture.cipher.encryptV2("test-only-body", context, old);
        });
        fixture.safetyEnvironment.withProperty("auth.direct-v2.safety-mode", "PRODUCTION")
                .withProperty("auth.direct-v2.backend-instances", "1")
                .withProperty("auth.direct-v2.external-restore-epoch", "2");
        fixture.jdbc.update("UPDATE auth_direct_projection_control SET readiness = 'PRODUCTION_READY'");
        fixture.jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ?", new byte[12]);
        assertThrows(com.muse.meomuneum.auth.directv2.model.DirectAuthFailure.class,
                () -> fixture.cipher.reserveV2(1, () -> {
                }));
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        byte[] derived = javax.crypto.KDF.getInstance("HKDF-SHA256").deriveData(
                javax.crypto.spec.HKDFParameterSpec.ofExtract().addIKM(key)
                        .addSalt("meomuneum/auth-replay/v2/hkdf-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .thenExpand("meomuneum/auth-replay/v2/aes-256-gcm"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8), 32));
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update("meomuneum/auth-replay/v2/key-id".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String keyId = HexFormat.of().formatHex(digest.digest(derived));
        fixture.jdbc.update("UPDATE auth_replay_key_pin SET key_id = ?", keyId);
        fixture.jdbc.update("INSERT INTO auth_replay_iv_allocators VALUES (?, ?, FALSE)", keyId, new byte[12]);
        fixture.jdbc.update("UPDATE auth_direct_projection_control SET projection_generation = 2, replay_key_id = ?",
                keyId);
        var properties = new com.muse.meomuneum.auth.config.AuthStateProperties(
                fixture.properties.hmacKey(), java.util.Base64.getEncoder().encodeToString(key), null,
                1800, 3600, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        var cipher = new com.muse.meomuneum.auth.application.RefreshReplayCipher(properties, fixture.allocator);
        var fresh = cipher.reserveV2(1, () -> {
        });
        assertFalse(fresh.keyId().equals(old.keyId()));
        fixture.transactions.run(() -> {
            fixture.projections.lockTicket(fresh);
            String replacement = cipher.encryptV2("new-test-only-body", context, fresh);
            assertEquals("new-test-only-body", cipher.decryptV2(replacement, context));
            return null;
        });
        assertThrows(AuthStateUnavailableException.class, () -> cipher.decryptV2(encrypted, context));
        assertThrows(AuthStateUnavailableException.class, () -> fixture.cipher.decryptV2(encrypted, context));
    }

}
