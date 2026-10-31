package com.muse.meomuneum.auth.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class DirectAuthOperationMigrationTest {
    private static final String BASELINE = "20261027205623";
    private static final String TARGET = "20261031002221";
    private static final String FLOW = "550e8400-e29b-41d4-a716-446655440000";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("direct_auth_migration").withUsername("test").withPassword("test");

    @Container
    private static final MySQLContainer FRESH = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("direct_auth_fresh").withUsername("test").withPassword("test");

    private static final String COLUMNS = """
            auth_browser_flows|flow_key_hash|char(43)|YES|null
            auth_browser_flows|flow_cookie_generation|bigint|NO|1
            auth_browser_flows|binding_cookie_generation|bigint|NO|0
            auth_browser_flows|binding_proof_hmac|binary(32)|YES|null
            auth_browser_flows|binding_cookie_expires_at|datetime(6)|YES|null
            auth_browser_flows|active_login_operation_id|char(36)|YES|null
            auth_browser_flows|current_direct_operation_kind|varchar(16)|YES|null
            auth_browser_flows|current_direct_operation_id|char(36)|YES|null
            auth_browser_flows|current_refresh_recovery_operation_id|char(36)|YES|null
            auth_browser_flows|current_refresh_recovery_source_operation_id|char(36)|YES|null
            auth_browser_flows|current_refresh_recovery_status|varchar(24)|YES|null
            auth_browser_flows|current_refresh_recovery_lease_expires_at|datetime(6)|YES|null
            auth_browser_flows|current_refresh_recovery_claim_flow_cookie_generation|bigint|YES|null
            auth_browser_flows|current_refresh_recovery_claim_refresh_generation|bigint|YES|null
            auth_browser_flows|current_refresh_recovery_result_flow_cookie_generation|bigint|YES|null
            auth_browser_flows|current_refresh_recovery_result_refresh_generation|bigint|YES|null
            auth_browser_sessions|refresh_cookie_mode|varchar(24)|NO|legacy
            auth_login_operations|flow_id|char(36)|NO|null
            auth_login_operations|operation_id|char(36)|NO|null
            auth_login_operations|starting_epoch|bigint|NO|null
            auth_login_operations|status|varchar(24)|NO|null
            auth_login_operations|proof_generation|bigint|YES|null
            auth_login_operations|lease_expires_at|datetime(6)|YES|null
            auth_login_operations|receipt_expires_at|datetime(6)|YES|null
            auth_login_operations|tombstone_purge_at|datetime(6)|YES|null
            auth_login_operations|result_epoch|bigint|YES|null
            auth_login_operations|result_revision|bigint|YES|null
            auth_login_operations|result_sid|varchar(64)|YES|null
            auth_login_operations|reason_code|varchar(40)|YES|null
            auth_login_operations|result_flow_cookie_generation|bigint|YES|null
            auth_login_operations|result_refresh_generation|bigint|YES|null
            auth_login_operations|recovery_result_epoch|bigint|YES|null
            auth_login_operations|recovery_result_revision|bigint|YES|null
            auth_login_operations|recovery_flow_cookie_generation|bigint|YES|null
            auth_login_operations|recovery_refresh_generation|bigint|YES|null
            auth_login_operations|recovery_completed_at|datetime(6)|YES|null
            auth_login_operations|created_at|datetime(6)|NO|null
            auth_login_operations|updated_at|datetime(6)|NO|null
            auth_login_operations|completed_at|datetime(6)|YES|null
            auth_direct_operation_receipts|flow_id|char(36)|NO|null
            auth_direct_operation_receipts|operation_kind|varchar(16)|NO|null
            auth_direct_operation_receipts|operation_id|char(36)|NO|null
            auth_direct_operation_receipts|starting_epoch|bigint|NO|null
            auth_direct_operation_receipts|original_flow_cookie_generation|bigint|YES|null
            auth_direct_operation_receipts|original_refresh_token_hmac|binary(32)|YES|null
            auth_direct_operation_receipts|result_epoch|bigint|YES|null
            auth_direct_operation_receipts|result_revision|bigint|YES|null
            auth_direct_operation_receipts|result_flow_cookie_generation|bigint|YES|null
            auth_direct_operation_receipts|result_refresh_generation|bigint|YES|null
            auth_direct_operation_receipts|status|varchar(16)|NO|null
            auth_direct_operation_receipts|receipt_expires_at|datetime(6)|NO|null
            auth_direct_operation_receipts|tombstone_purge_at|datetime(6)|NO|null
            auth_direct_operation_receipts|encrypted_result_response_body|text|YES|null
            auth_direct_operation_receipts|encrypted_result_refresh_token|text|YES|null
            auth_direct_operation_receipts|expired_cookie_names|text|YES|null
            auth_direct_operation_receipts|created_at|datetime(6)|NO|null
            auth_direct_operation_receipts|completed_at|datetime(6)|YES|null
            auth_flow_rebootstrap_operations|flow_id|char(36)|NO|null
            auth_flow_rebootstrap_operations|operation_id|char(36)|NO|null
            auth_flow_rebootstrap_operations|starting_epoch|bigint|NO|null
            auth_flow_rebootstrap_operations|binding_generation|bigint|YES|null
            auth_flow_rebootstrap_operations|status|varchar(16)|NO|null
            auth_flow_rebootstrap_operations|result_epoch|bigint|YES|null
            auth_flow_rebootstrap_operations|result_revision|bigint|YES|null
            auth_flow_rebootstrap_operations|result_flow_cookie_generation|bigint|YES|null
            auth_flow_rebootstrap_operations|receipt_expires_at|datetime(6)|NO|null
            auth_flow_rebootstrap_operations|tombstone_purge_at|datetime(6)|NO|null
            auth_flow_rebootstrap_operations|created_at|datetime(6)|NO|null
            auth_flow_rebootstrap_operations|completed_at|datetime(6)|YES|null
            auth_flow_rebootstrap_operations|result_flow_cookie_ciphertext|text|YES|null
            auth_refresh_recovery_operations|flow_id|char(36)|NO|null
            auth_refresh_recovery_operations|recovery_operation_id|char(36)|NO|null
            auth_refresh_recovery_operations|source_refresh_operation_id|char(36)|NO|null
            auth_refresh_recovery_operations|starting_epoch|bigint|NO|null
            auth_refresh_recovery_operations|binding_generation|bigint|NO|null
            auth_refresh_recovery_operations|claim_flow_cookie_generation|bigint|NO|null
            auth_refresh_recovery_operations|claim_refresh_generation|bigint|NO|null
            auth_refresh_recovery_operations|status|varchar(24)|NO|null
            auth_refresh_recovery_operations|owner_token|binary(16)|YES|null
            auth_refresh_recovery_operations|lease_expires_at|datetime(6)|YES|null
            auth_refresh_recovery_operations|result_session_sid|varchar(64)|YES|null
            auth_refresh_recovery_operations|result_epoch|bigint|YES|null
            auth_refresh_recovery_operations|result_revision|bigint|YES|null
            auth_refresh_recovery_operations|result_flow_cookie_generation|bigint|YES|null
            auth_refresh_recovery_operations|result_refresh_generation|bigint|YES|null
            auth_refresh_recovery_operations|encrypted_result_response_body|blob|YES|null
            auth_refresh_recovery_operations|encrypted_result_refresh_cookie|blob|YES|null
            auth_refresh_recovery_operations|encrypted_result_flow_cookie|blob|YES|null
            auth_refresh_recovery_operations|receipt_expires_at|datetime(6)|NO|null
            auth_refresh_recovery_operations|tombstone_purge_at|datetime(6)|NO|null
            auth_refresh_recovery_operations|created_at|datetime(6)|NO|null
            auth_refresh_recovery_operations|updated_at|datetime(6)|NO|null
            auth_refresh_recovery_operations|completed_at|datetime(6)|YES|null
            auth_flow_bootstrap_operations|operation_id|char(36)|NO|null
            auth_flow_bootstrap_operations|request_fingerprint|binary(32)|NO|null
            auth_flow_bootstrap_operations|initial_proof_fingerprint|binary(32)|YES|null
            auth_flow_bootstrap_operations|flow_id|char(36)|YES|null
            auth_flow_bootstrap_operations|status|varchar(16)|NO|null
            auth_flow_bootstrap_operations|result_flow_status|varchar(24)|YES|null
            auth_flow_bootstrap_operations|result_epoch|bigint|YES|null
            auth_flow_bootstrap_operations|result_revision|bigint|YES|null
            auth_flow_bootstrap_operations|result_flow_cookie_generation|bigint|YES|null
            auth_flow_bootstrap_operations|result_binding_cookie_generation|bigint|YES|null
            auth_flow_bootstrap_operations|encrypted_exact_response|blob|YES|null
            auth_flow_bootstrap_operations|receipt_expires_at|datetime(6)|NO|null
            auth_flow_bootstrap_operations|tombstone_purge_at|datetime(6)|NO|null
            auth_flow_bootstrap_operations|created_at|datetime(6)|NO|null
            auth_flow_bootstrap_operations|updated_at|datetime(6)|NO|null
            auth_flow_bootstrap_operations|completed_at|datetime(6)|YES|null
            auth_replay_iv_allocators|key_id|varchar(128)|NO|null
            auth_replay_iv_allocators|next_iv|binary(12)|NO|null
            auth_replay_iv_allocators|exhausted|tinyint(1)|NO|0
            auth_replay_key_pin|singleton_id|tinyint|NO|null
            auth_replay_key_pin|key_id|char(64)|NO|null
            """;
    private static final String INDEXES = """
            auth_browser_flows|UK_AUTH_BROWSER_FLOWS_FLOW_KEY_HASH|0|flow_key_hash
            auth_login_operations|PRIMARY|0|flow_id,operation_id
            auth_login_operations|IDX_AUTH_LOGIN_OPERATIONS_FLOW_STATUS_EXPIRY|1|flow_id,status,receipt_expires_at
            auth_login_operations|IDX_AUTH_LOGIN_OPERATIONS_RECEIPT_EXPIRY|1|receipt_expires_at,flow_id,operation_id
            auth_login_operations|IDX_AUTH_LOGIN_OPERATIONS_TOMBSTONE_PURGE|1|tombstone_purge_at,flow_id,operation_id
            auth_login_operations|IDX_AUTH_LOGIN_OPERATIONS_LEASE_EXPIRY|1|status,lease_expires_at,flow_id,operation_id
            auth_direct_operation_receipts|PRIMARY|0|flow_id,operation_kind,operation_id
            auth_direct_operation_receipts|IDX_AUTH_DIRECT_OPERATION_RECEIPTS_FLOW_EXPIRY|1|flow_id,receipt_expires_at
            auth_direct_operation_receipts|IDX_AUTH_DIRECT_OPERATION_RECEIPTS_RECEIPT_EXPIRY|1|receipt_expires_at,flow_id,operation_kind,operation_id
            auth_direct_operation_receipts|IDX_AUTH_DIRECT_OPERATION_RECEIPTS_TOMBSTONE_PURGE|1|tombstone_purge_at,flow_id,operation_kind,operation_id
            auth_flow_rebootstrap_operations|PRIMARY|0|flow_id,operation_id
            auth_flow_rebootstrap_operations|IDX_AUTH_FLOW_REBOOTSTRAP_OPS_FLOW_EXPIRY|1|flow_id,receipt_expires_at
            auth_flow_rebootstrap_operations|IDX_AUTH_FLOW_REBOOTSTRAP_OPS_RECEIPT_EXPIRY|1|receipt_expires_at,flow_id,operation_id
            auth_flow_rebootstrap_operations|IDX_AUTH_FLOW_REBOOTSTRAP_OPS_TOMBSTONE_PURGE|1|tombstone_purge_at,flow_id,operation_id
            auth_refresh_recovery_operations|PRIMARY|0|flow_id,recovery_operation_id
            auth_refresh_recovery_operations|IDX_AUTH_REFRESH_RECOVERY_SOURCE|1|flow_id,source_refresh_operation_id,created_at,recovery_operation_id
            auth_refresh_recovery_operations|IDX_AUTH_REFRESH_RECOVERY_LEASE|1|status,lease_expires_at,flow_id,recovery_operation_id
            auth_refresh_recovery_operations|IDX_AUTH_REFRESH_RECOVERY_RECEIPT_EXPIRY|1|receipt_expires_at,flow_id,recovery_operation_id
            auth_refresh_recovery_operations|IDX_AUTH_REFRESH_RECOVERY_TOMBSTONE_PURGE|1|tombstone_purge_at,flow_id,recovery_operation_id
            auth_flow_bootstrap_operations|PRIMARY|0|operation_id
            auth_flow_bootstrap_operations|IDX_AUTH_FLOW_BOOTSTRAP_FLOW_STATUS_EXPIRY|1|flow_id,status,receipt_expires_at
            auth_flow_bootstrap_operations|IDX_AUTH_FLOW_BOOTSTRAP_RECEIPT_EXPIRY|1|receipt_expires_at,operation_id
            auth_flow_bootstrap_operations|IDX_AUTH_FLOW_BOOTSTRAP_TOMBSTONE_PURGE|1|tombstone_purge_at,operation_id
            auth_replay_iv_allocators|PRIMARY|0|key_id
            auth_replay_key_pin|PRIMARY|0|singleton_id
            """;
    private static final String FOREIGN_KEYS = """
            auth_login_operations|FK_AUTH_LOGIN_OPERATIONS_FLOW|CASCADE|RESTRICT
            auth_direct_operation_receipts|FK_AUTH_DIRECT_OPERATION_RECEIPTS_FLOW|CASCADE|RESTRICT
            auth_flow_rebootstrap_operations|FK_AUTH_FLOW_REBOOTSTRAP_OPERATIONS_FLOW|CASCADE|RESTRICT
            auth_refresh_recovery_operations|FK_AUTH_REFRESH_RECOVERY_OPERATIONS_FLOW|RESTRICT|RESTRICT
            """;

    @Test
    void upgradesExistingAuthStateAndValidatesSchema() {
        migrate(BASELINE);
        JdbcTemplate jdbc = jdbc();
        jdbc.update("INSERT INTO auth_browser_flows (flow_id,flow_cookie_hash,cookie_expires_at,created_at,updated_at) "
                + "VALUES (?,REPEAT('a',64),NOW(6),NOW(6),NOW(6))", FLOW);
        jdbc.update("INSERT INTO auth_browser_sessions (sid,flow_id,flow_epoch,user_id,status,idle_expires_at,"
                + "absolute_expires_at,encrypted_current_refresh_token,created_at,updated_at) "
                + "VALUES ('sid',?,1,1,'ACTIVE',NOW(6),NOW(6),'rrc:v1:session',NOW(6),NOW(6))", FLOW);
        jdbc.update("INSERT INTO auth_flow_operations (flow_id,operation_kind,operation_id,request_hash,started_epoch,"
                + "result_epoch,result_revision,encrypted_result_refresh_token,committed_at) "
                + "VALUES (?,'REFRESH',?,REPEAT('b',64),1,1,1,'rrc:v1:receipt',NOW(6))", FLOW, FLOW);
        jdbc.update("INSERT INTO auth_queue_epoch_slots (flow_id,flow_epoch,created_at,updated_at) "
                + "VALUES (?,1,NOW(6),NOW(6))", FLOW);
        var operations = jdbc.queryForList("SELECT * FROM auth_flow_operations");
        var queue = jdbc.queryForList("SELECT * FROM auth_queue_epoch_slots");
        assertEquals(1, migrate(TARGET).migrationsExecuted);
        assertEquals(operations, jdbc.queryForList("SELECT * FROM auth_flow_operations"));
        assertEquals(queue, jdbc.queryForList("SELECT * FROM auth_queue_epoch_slots"));
        assertEquals("rrc:v1:session", jdbc.queryForObject(
                "SELECT encrypted_current_refresh_token FROM auth_browser_sessions WHERE sid='sid'", String.class));
        assertEquals("LEGACY", jdbc.queryForObject(
                "SELECT refresh_cookie_mode FROM auth_browser_sessions WHERE sid='sid'", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT flow_cookie_generation FROM auth_browser_flows WHERE flow_id=?", Long.class, FLOW));
        verifySchema(jdbc);
        verifyConstraints(jdbc);
        assertEquals(0, migrate(TARGET).migrationsExecuted);
    }

    @Test
    void freshMigrationHasSameSchema() {
        String url = FRESH.getJdbcUrl();
        Flyway.configure().dataSource(url, FRESH.getUsername(), FRESH.getPassword()).load().migrate();
        JdbcTemplate fresh = new JdbcTemplate(
                new DriverManagerDataSource(url, FRESH.getUsername(), FRESH.getPassword()));
        verifySchema(fresh);
    }

    private void verifySchema(JdbcTemplate jdbc) {
        for (String line : COLUMNS.strip().split("\n")) {
            String[] expected = line.strip().split("\\|");
            var rows = jdbc.queryForList("SELECT LOWER(COLUMN_TYPE) AS type, IS_NULLABLE AS nullable, "
                    + "LOWER(COALESCE(COLUMN_DEFAULT,'null')) AS default_value FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", expected[0], expected[1]);
            assertEquals(1, rows.size(), line);
            assertEquals(expected[2], rows.getFirst().get("type"), line);
            assertEquals(expected[3], rows.getFirst().get("nullable"), line);
            assertEquals(expected[4], rows.getFirst().get("default_value"), line);
        }
        for (String line : INDEXES.strip().split("\n")) {
            String[] expected = line.strip().split("\\|");
            var rows = jdbc.queryForList("SELECT NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols "
                    + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
                    + "AND INDEX_NAME=? GROUP BY NON_UNIQUE", expected[0], expected[1]);
            assertEquals(1, rows.size(), line);
            assertEquals(Integer.parseInt(expected[2]), ((Number) rows.getFirst().get("NON_UNIQUE")).intValue(), line);
            assertEquals(expected[3], rows.getFirst().get("cols"), line);
        }
        for (String line : FOREIGN_KEYS.strip().split("\n")) {
            String[] expected = line.strip().split("\\|");
            var rows = jdbc.queryForList(
                    "SELECT DELETE_RULE, UPDATE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?",
                    expected[0], expected[1]);
            assertEquals(1, rows.size(), line);
            assertEquals(expected[2], rows.getFirst().get("DELETE_RULE"), line);
            assertEquals(expected[3], rows.getFirst().get("UPDATE_RULE"), line);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='auth_flow_bootstrap_operations'", Integer.class));
        assertEquals("ascii_bin", jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='auth_browser_flows' AND COLUMN_NAME='flow_key_hash'",
                String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_replay_key_pin", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_replay_iv_allocators", Integer.class));
    }

    private void verifyConstraints(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO auth_refresh_recovery_operations (flow_id,recovery_operation_id,"
                + "source_refresh_operation_id,starting_epoch,binding_generation,claim_flow_cookie_generation,claim_refresh_generation,status,receipt_expires_at,"
                + "tombstone_purge_at,created_at,updated_at) VALUES (?,?,?,1,1,1,1,'TOMBSTONE',NOW(6),NOW(6),NOW(6),NOW(6))",
                FLOW, FLOW, FLOW);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM auth_browser_flows WHERE flow_id=?", FLOW));
        jdbc.update("DELETE FROM auth_refresh_recovery_operations WHERE flow_id=?", FLOW);
        assertEquals(1, jdbc.update("DELETE FROM auth_browser_flows WHERE flow_id=?", FLOW));
        jdbc.update("INSERT INTO auth_replay_iv_allocators (key_id,next_iv) VALUES ('key',UNHEX(REPEAT('00',12)))");
        assertEquals(0, jdbc.queryForObject("SELECT exhausted FROM auth_replay_iv_allocators WHERE key_id='key'",
                Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO auth_replay_iv_allocators (key_id,next_iv) VALUES ('key',UNHEX(REPEAT('00',12)))"));
        jdbc.update("INSERT INTO auth_replay_key_pin (singleton_id,key_id) VALUES (1,REPEAT('a',64))");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO auth_replay_key_pin (singleton_id,key_id) VALUES (1,REPEAT('b',64))"));
    }

    private org.flywaydb.core.api.output.MigrateResult migrate(String target) {
        Flyway flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(target).load();
        var result = flyway.migrate();
        assertTrue(flyway.validateWithResult().validationSuccessful);
        return result;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }
}
