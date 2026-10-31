package com.muse.meomuneum.auth.directv2.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.auth.directv2.application.DirectAuthTransactionRunner;

@Testcontainers
class DirectAuthOperationRepositoryTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0");

    @Test
    void ownerFenceRejectsExpiredChangedOwnerAndRecreatedOperation() throws Exception {
        var grant = MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e",
                "GRANT SELECT ON performance_schema.* TO '" + MYSQL.getUsername() + "'@'%'");
        assertTrue(grant.getExitCode() == 0);
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20261024142649__create_auth_browser_flow_authority.sql"),
                new ClassPathResource("db/migration/V20261031002221__add_direct_auth_operations.sql"),
                new ClassPathResource("db/migration/V20261031150133__add_direct_auth_recovery_constraints.sql"))
                .execute(source);
        jdbc.update("""
                INSERT INTO auth_browser_flows (flow_id, flow_cookie_hash, cookie_expires_at, created_at,
                    updated_at, active_login_operation_id)
                VALUES ('flow', 'hash', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'operation')
                """);
        byte[] owner = new byte[16];
        Arrays.fill(owner, (byte) 1);
        byte[] replacement = new byte[16];
        Arrays.fill(replacement, (byte) 2);
        jdbc.update("""
                INSERT INTO auth_login_operations (flow_id, operation_id, starting_epoch, status, proof_generation,
                    owner_token, lease_expires_at, created_at, updated_at)
                VALUES ('flow', 'operation', 1, 'IN_PROGRESS', 1, ?,
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, owner);
        var repository = new DirectAuthOperationRepository(jdbc, new DirectAuthFlowRepository(jdbc));
        var transactions = new DirectAuthTransactionRunner(new DataSourceTransactionManager(source));
        assertThrows(IllegalStateException.class, () -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner));
        assertTrue(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner).isPresent()));
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, replacement)
                .isPresent()));
        var valid = transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner).orElseThrow());
        assertFalse(valid.ownedBy(owner, 1, 1, null));
        jdbc.update("UPDATE auth_browser_flows SET lifecycle = 'AUTHENTICATED' WHERE flow_id = 'flow'");
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner).isPresent()));
        jdbc.update("UPDATE auth_browser_flows SET lifecycle = 'ANONYMOUS' WHERE flow_id = 'flow'");
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 2, 1, owner).isPresent()));
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 2, owner).isPresent()));
        jdbc.update("UPDATE auth_login_operations SET receipt_expires_at = CURRENT_TIMESTAMP(6)");
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner).isPresent()));
        jdbc.update("UPDATE auth_login_operations SET receipt_expires_at = NULL");
        verifyLeaseExpiresWhileWaiting(jdbc, transactions, repository, owner);
        jdbc.update("UPDATE auth_login_operations SET lease_expires_at = CURRENT_TIMESTAMP(6)");
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner).isPresent()));
        jdbc.update("DELETE FROM auth_login_operations");
        jdbc.update("""
                INSERT INTO auth_login_operations (flow_id, operation_id, starting_epoch, status, proof_generation,
                    owner_token, lease_expires_at, created_at, updated_at)
                VALUES ('flow', 'operation', 1, 'IN_PROGRESS', 1, ?,
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, replacement);
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, owner).isPresent()));
        assertTrue(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, replacement)
                .isPresent()));
        jdbc.update("UPDATE auth_browser_flows SET active_login_operation_id = NULL WHERE flow_id = 'flow'");
        assertFalse(transactions.run(() -> repository.lockOwnedLogin("flow", "operation", 1, 1, replacement)
                .isPresent()));
    }
    private void verifyLeaseExpiresWhileWaiting(JdbcTemplate jdbc, DirectAuthTransactionRunner transactions,
            DirectAuthOperationRepository repository, byte[] owner) throws Exception {
        jdbc.update("""
                UPDATE auth_login_operations SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND)
                """);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var connectionId = new AtomicLong();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> transactions.run(() -> {
                jdbc.queryForObject("SELECT operation_id FROM auth_login_operations WHERE flow_id = 'flow' FOR UPDATE",
                        String.class);
                locked.countDown();
                try {
                    if (!release.await(45, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Lock release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var waiter = executor.submit(() -> transactions.run(() -> {
                connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                return repository.lockOwnedLogin("flow", "operation", 1, 1, owner).isPresent();
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            boolean waiting = false;
            boolean expired = false;
            while (System.nanoTime() < deadline && !(waiting && expired)) {
                var observation = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.threads t ON t.THREAD_ID = w.REQUESTING_THREAD_ID
                            WHERE t.PROCESSLIST_ID = ?) AS waiting,
                            lease_expires_at > CURRENT_TIMESTAMP(6) AS valid
                        FROM auth_login_operations WHERE flow_id = 'flow' AND operation_id = 'operation'
                        """, (row, index) -> new boolean[]{row.getBoolean("waiting"), row.getBoolean("valid")},
                        connectionId.get());
                if (!waiting && observation[0]) {
                    assertTrue(observation[1], "Lease expired before the operation lock wait was observed");
                    waiting = true;
                }
                expired = !observation[1];
                if (!(waiting && expired)) {
                    Thread.sleep(20);
                }
            }
            assertTrue(waiting, "The operation lock wait was not observed");
            assertTrue(expired, "The database lease did not expire");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertFalse(waiter.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

}
