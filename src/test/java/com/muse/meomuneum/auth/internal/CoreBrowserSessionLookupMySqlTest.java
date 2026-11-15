package com.muse.meomuneum.auth.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.security.SessionPair;
import com.muse.meomuneum.user.repository.UserRepository;

@Testcontainers
class CoreBrowserSessionLookupMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("browser_authority").withUsername("test").withPassword("isolated-authority-test");

    @Test
    void databaseRevocationEpochAndExpiryOverrideAnActiveHazelcastSnapshot() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("20261024142649").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        String flow = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO auth_browser_flows
                    (flow_id,flow_cookie_hash,flow_epoch,revision,lifecycle,active_session_sid,
                     cookie_expires_at,created_at,updated_at)
                VALUES (?,?,1,1,'AUTHENTICATED',?,TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),
                        UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, flow, flow.replace("-", "").repeat(2), sid);
        jdbc.update("""
                INSERT INTO auth_browser_sessions
                    (sid,flow_id,flow_epoch,user_id,status,idle_expires_at,absolute_expires_at,created_at,updated_at)
                VALUES (?,?,1,7,'ACTIVE',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),
                        TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, sid, flow);
        var store = mock(HazelcastAuthStateStore.class);
        var snapshot = mock(HazelcastAuthStateStore.AuthSession.class);
        when(snapshot.sid()).thenReturn(sid);
        when(snapshot.userId()).thenReturn(7L);
        when(snapshot.status()).thenReturn(HazelcastAuthStateStore.SessionStatus.ACTIVE);
        when(snapshot.generation()).thenReturn(1L);
        when(snapshot.refreshIdleExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));
        when(snapshot.refreshAbsoluteExpiresAt()).thenReturn(Instant.now().plusSeconds(7200));
        when(store.getSessions(Set.of(sid))).thenReturn(Map.of(sid, snapshot));
        var users = mock(UserRepository.class);
        when(users.findActiveIds(List.of(7L))).thenReturn(Set.of(7L));
        var pair = new SessionPair(7, sid);
        try (var service = new CoreSessionValidationService(store, users, new CoreBrowserSessionLookup(jdbc))) {
            assertTrue(service.validate(List.of(pair)).getFirst().active());
            jdbc.update("UPDATE auth_browser_sessions SET status='REVOKED' WHERE sid=?", sid);
            assertFalse(service.validate(List.of(pair)).getFirst().active(),
                    "a stale ACTIVE cache must not override a committed DB revocation");
            jdbc.update("UPDATE auth_browser_sessions SET status='ACTIVE' WHERE sid=?", sid);
            jdbc.update("UPDATE auth_browser_flows SET flow_epoch=2 WHERE flow_id=?", flow);
            assertFalse(service.validate(List.of(pair)).getFirst().active(),
                    "a session from an earlier browser epoch must not be admitted");
            jdbc.update("UPDATE auth_browser_flows SET flow_epoch=1 WHERE flow_id=?", flow);
            jdbc.update("UPDATE auth_browser_sessions SET idle_expires_at=UTC_TIMESTAMP(6) WHERE sid=?", sid);
            assertFalse(service.validate(List.of(pair)).getFirst().active(),
                    "the DB expiry must override a later cache expiry");
        }
    }
}
