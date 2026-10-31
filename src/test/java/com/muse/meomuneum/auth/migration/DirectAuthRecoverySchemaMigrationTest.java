package com.muse.meomuneum.auth.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class DirectAuthRecoverySchemaMigrationTest {
    private static final String BASE = "20261031002221";
    private static final String FLOW = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OTHER = "550e8400-e29b-41d4-a716-446655440001";
    private static final String SOURCE = "550e8400-e29b-41d4-a716-446655440002";
    private static final String RECOVERY = "550e8400-e29b-41d4-a716-446655440003";
    @Container
    private static final MySQLContainer UPGRADE = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("recovery_upgrade").withUsername("test").withPassword("test");
    @Container
    private static final MySQLContainer FRESH = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("recovery_fresh").withUsername("test").withPassword("test");

    @Container
    private static final MySQLContainer ORPHAN = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("recovery_orphan").withUsername("test").withPassword("test");

    @Test
    void upgradeRejectsExistingOrphanWithoutChangingIt() {
        migrate(ORPHAN, BASE);
        JdbcTemplate db = jdbc(ORPHAN);
        flow(db, FLOW);
        recovery(db, FLOW, RECOVERY, SOURCE, "REFRESH");
        assertThrows(FlywayException.class, () -> migrate(ORPHAN, null));
        assertEquals(SOURCE, db.queryForObject("SELECT source_refresh_operation_id "
                + "FROM auth_refresh_recovery_operations WHERE flow_id=?", String.class, FLOW));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM auth_refresh_recovery_operations", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='auth_login_operations' AND COLUMN_NAME='owner_token'",
                Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='auth_direct_operation_receipts' AND COLUMN_NAME='result_session_sid'",
                Integer.class));
    }

    @Test
    void upgradePreservesLegacyRowsAndEnforcesSourceReference() {
        migrate(UPGRADE, BASE);
        JdbcTemplate db = jdbc(UPGRADE);
        flow(db, FLOW);
        flow(db, OTHER);
        source(db, FLOW, "REFRESH", SOURCE);
        source(db, FLOW, "LOGOUT", OTHER);
        recovery(db, FLOW, RECOVERY, SOURCE, "REFRESH");
        migrate(UPGRADE, null);
        verifyMetadata(db);
        source(db, FLOW, "refresh", OTHER);
        source(db, OTHER, "REFRESH ", SOURCE);
        assertThrows(DataIntegrityViolationException.class, () -> recovery(db, OTHER, RECOVERY, SOURCE, "REFRESH"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM auth_refresh_recovery_operations "
                + "WHERE client_expires_at_ms IS NULL AND effective_expires_at_ms IS NULL "
                + "AND source_operation_kind='REFRESH'", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> recovery(db, OTHER, OTHER, SOURCE, "REFRESH"));
        assertThrows(DataAccessException.class, () -> recovery(db, FLOW, OTHER, OTHER, "LOGOUT"));
        assertThrows(DataAccessException.class, () -> recovery(db, FLOW, OTHER, SOURCE, "refresh"));
        assertThrows(DataIntegrityViolationException.class, () -> recovery(db, FLOW, OTHER, OTHER, "REFRESH"));
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update(
                        "DELETE FROM auth_direct_operation_receipts WHERE flow_id=? AND operation_kind='REFRESH'",
                        FLOW));
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update("DELETE FROM auth_browser_flows WHERE flow_id=?", FLOW));
        db.update("UPDATE auth_refresh_recovery_operations SET binding_generation=NULL, "
                + "claim_flow_cookie_generation=NULL, claim_refresh_generation=NULL, status='TOMBSTONE' WHERE flow_id=?",
                FLOW);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM auth_refresh_recovery_operations WHERE "
                + "binding_generation IS NULL AND claim_flow_cookie_generation IS NULL AND claim_refresh_generation IS NULL",
                Integer.class));
        db.update("DELETE FROM auth_refresh_recovery_operations WHERE flow_id=?", FLOW);
        assertEquals(1, db.update(
                "DELETE FROM auth_direct_operation_receipts WHERE flow_id=? AND operation_kind='REFRESH'", FLOW));
        assertEquals(0, migrate(UPGRADE, null));
    }

    @Test
    void freshSchemaHasIdenticalMetadata() {
        migrate(FRESH, null);
        verifyMetadata(jdbc(FRESH));
    }

    private static void verifyMetadata(JdbcTemplate db) {
        String columns = """
                auth_login_operations|owner_token|binary(16)
                auth_direct_operation_receipts|result_session_sid|varchar(64)
                auth_direct_operation_receipts|original_flow_cookie_hmac|binary(32)
                auth_direct_operation_receipts|encrypted_result_flow_cookie|text
                auth_direct_operation_receipts|client_expires_at_ms|bigint
                auth_direct_operation_receipts|effective_expires_at_ms|bigint
                auth_refresh_recovery_operations|client_expires_at_ms|bigint
                auth_refresh_recovery_operations|effective_expires_at_ms|bigint
                auth_refresh_recovery_operations|binding_generation|bigint
                auth_refresh_recovery_operations|claim_flow_cookie_generation|bigint
                auth_refresh_recovery_operations|claim_refresh_generation|bigint
                """;
        for (String line : columns.strip().split("\n")) {
            String[] parts = line.strip().split("\\|");
            var rows = db.queryForList("SELECT LOWER(COLUMN_TYPE) AS type, IS_NULLABLE, COLUMN_DEFAULT "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                    parts[0], parts[1]);
            assertEquals(1, rows.size(), line);
            assertEquals(parts[2], rows.getFirst().get("type"), line);
            assertEquals("YES", rows.getFirst().get("IS_NULLABLE"), line);
            assertEquals(null, rows.getFirst().get("COLUMN_DEFAULT"), line);
        }
        assertEquals("NO PAD", db.queryForObject("SELECT PAD_ATTRIBUTE FROM information_schema.COLLATIONS "
                + "WHERE COLLATION_NAME='utf8mb4_0900_bin'", String.class));
        assertEquals(2,
                db.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND ((TABLE_NAME='auth_direct_operation_receipts' AND COLUMN_NAME='operation_kind') "
                        + "OR (TABLE_NAME='auth_refresh_recovery_operations' AND COLUMN_NAME='source_operation_kind')) "
                        + "AND COLLATION_NAME='utf8mb4_0900_bin'", Integer.class));
        assertEquals("flow_id,source_operation_kind,source_refresh_operation_id", db.queryForObject(
                "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='auth_refresh_recovery_operations' "
                        + "AND INDEX_NAME='IDX_AUTH_REFRESH_RECOVERY_SOURCE_FK'",
                String.class));
        assertEquals("RESTRICT|RESTRICT", db.queryForObject("SELECT CONCAT(DELETE_RULE,'|',UPDATE_RULE) "
                + "FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() "
                + "AND CONSTRAINT_NAME='FK_AUTH_REFRESH_RECOVERY_SOURCE'", String.class));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k "
                + "JOIN information_schema.COLUMNS c ON c.TABLE_SCHEMA=k.TABLE_SCHEMA AND c.TABLE_NAME=k.TABLE_NAME AND c.COLUMN_NAME=k.COLUMN_NAME "
                + "JOIN information_schema.COLUMNS p ON p.TABLE_SCHEMA=k.REFERENCED_TABLE_SCHEMA AND p.TABLE_NAME=k.REFERENCED_TABLE_NAME AND p.COLUMN_NAME=k.REFERENCED_COLUMN_NAME "
                + "WHERE k.CONSTRAINT_SCHEMA=DATABASE() AND k.CONSTRAINT_NAME='FK_AUTH_REFRESH_RECOVERY_SOURCE' "
                + "AND c.COLUMN_TYPE=p.COLUMN_TYPE AND c.COLLATION_NAME=p.COLLATION_NAME", Integer.class));
    }

    private static void flow(JdbcTemplate db, String id) {
        db.update("INSERT INTO auth_browser_flows (flow_id,flow_cookie_hash,cookie_expires_at,created_at,updated_at) "
                + "VALUES (?,SHA2(?,256),NOW(6),NOW(6),NOW(6))", id, id);
    }

    private static void source(JdbcTemplate db, String flow, String kind, String id) {
        db.update(
                "INSERT INTO auth_direct_operation_receipts (flow_id,operation_kind,operation_id,starting_epoch,status,"
                        + "receipt_expires_at,tombstone_purge_at,created_at) VALUES (?,?,?,1,'SUCCEEDED',NOW(6),NOW(6),NOW(6))",
                flow, kind, id);
    }

    private static void recovery(JdbcTemplate db, String flow, String id, String source, String kind) {
        if (kind.equals("REFRESH")) {
            db.update(
                    "INSERT INTO auth_refresh_recovery_operations (flow_id,recovery_operation_id,source_refresh_operation_id,"
                            + "starting_epoch,binding_generation,claim_flow_cookie_generation,claim_refresh_generation,status,receipt_expires_at,"
                            + "tombstone_purge_at,created_at,updated_at) VALUES (?,?,?,1,1,1,1,'IN_PROGRESS',NOW(6),NOW(6),NOW(6),NOW(6))",
                    flow, id, source);
        } else {
            db.update(
                    "INSERT INTO auth_refresh_recovery_operations (flow_id,recovery_operation_id,source_refresh_operation_id,"
                            + "source_operation_kind,starting_epoch,status,receipt_expires_at,tombstone_purge_at,created_at,updated_at) "
                            + "VALUES (?,?,?,?,1,'IN_PROGRESS',NOW(6),NOW(6),NOW(6),NOW(6))",
                    flow, id, source, kind);
        }
    }

    private static int migrate(MySQLContainer mysql, String target) {
        var config = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        if (target != null) {
            config.target(target);
        }
        Flyway flyway = config.load();
        int count = flyway.migrate().migrationsExecuted;
        assertTrue(flyway.validateWithResult().validationSuccessful);
        return count;
    }

    private static JdbcTemplate jdbc(MySQLContainer mysql) {
        return new JdbcTemplate(
                new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    }
}
