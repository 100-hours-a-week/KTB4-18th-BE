package com.muse.meomuneum.auth.directv2.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

@Testcontainers
class DirectAuthNonceAllocatorTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0");

    @Test
    void committedReservationsSurviveOuterRollbackAndMultipleInstances() throws Exception {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20261024142649__create_auth_browser_flow_authority.sql"),
                new ClassPathResource("db/migration/V20261031002221__add_direct_auth_operations.sql"),
                new ClassPathResource("db/migration/V20261031150133__add_direct_auth_recovery_constraints.sql"))
                .execute(source);
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
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status -> {
            first.reserve(id);
            throw new IllegalStateException();
        }));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 40).mapToObj(i -> executor.submit(
                    () -> HexFormat.of().formatHex((i % 2 == 0 ? first : second).reserve(id)))).toList();
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
        assertEquals("000000000000000000000029", HexFormat.of().formatHex(first.reserve(id)));
        assertThrows(AuthStateUnavailableException.class, () -> first.reserve("b".repeat(64)));
        var unavailable = new DirectAuthNonceAllocator(jdbc, manager,
                (DirectAuthNonceAllocator.RestoreSafetyGate) null);
        assertThrows(AuthStateUnavailableException.class, () -> unavailable.reserve(id));
        approved.set(false);
        jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ? WHERE key_id = ?", new byte[12], id);
        assertThrows(AuthStateUnavailableException.class, () -> first.reserve(id));
        jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ? WHERE key_id = ?",
                HexFormat.of().parseHex("00000000000000000000002a"), id);
        approved.set(true);
        assertEquals("00000000000000000000002a", HexFormat.of().formatHex(first.reserve(id)));
        jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ?, exhausted = FALSE WHERE key_id = ?",
                HexFormat.of().parseHex("ffffffffffffffffffffffff"), id);
        assertEquals("ffffffffffffffffffffffff", HexFormat.of().formatHex(first.reserve(id)));
        assertThrows(AuthStateUnavailableException.class, () -> first.reserve(id));
    }
}
