package com.muse.meomuneum.auth.directv2.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
class DirectAuthFlowRepositoryTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0");
    private JdbcTemplate jdbc;
    private DirectAuthFlowRepository repository;
    private DirectAuthTransactionRunner transactions;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new DirectAuthFlowRepository(jdbc);
        transactions = new DirectAuthTransactionRunner(new DataSourceTransactionManager(dataSource));
        if (jdbc.queryForList("SHOW TABLES LIKE 'auth_browser_flows'", String.class).isEmpty()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V20261024142649__create_auth_browser_flow_authority.sql"),
                    new ClassPathResource("db/migration/V20261031002221__add_direct_auth_operations.sql"))
                    .execute(dataSource);
        }
        jdbc.update("DELETE FROM auth_browser_flows");
        jdbc.update("INSERT INTO auth_browser_flows (flow_id, flow_cookie_hash, cookie_expires_at, created_at, "
                + "updated_at) VALUES ('flow', 'hash', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    @Test
    void requiresTransactionAndMapsNullableValues() {
        assertThrows(IllegalStateException.class, () -> repository.lock("flow"));
        var flow = transactions.run(() -> repository.lock("flow").orElseThrow());
        assertEquals(1, flow.epoch());
        assertEquals(1, flow.revision());
        assertTrue(transactions.run(() -> repository.lock("missing").isEmpty()));
    }

    @Test
    void serializesContendersAndRollsBackChanges() throws Exception {
        var firstLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> assertThrows(IllegalStateException.class, () -> transactions.run(() -> {
                repository.lock("flow").orElseThrow();
                jdbc.update("UPDATE auth_browser_flows SET revision = 2 WHERE flow_id = 'flow'");
                firstLocked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                throw new IllegalStateException("rollback");
            })));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> transactions.run(() -> {
                secondStarted.countDown();
                return repository.lock("flow").orElseThrow().revision();
            }));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(1, second.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
