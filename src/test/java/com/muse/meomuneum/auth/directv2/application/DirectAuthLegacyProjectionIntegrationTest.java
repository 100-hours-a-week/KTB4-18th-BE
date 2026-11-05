package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;

class DirectAuthLegacyProjectionIntegrationTest {
    @Test
    void publicFacadePublishesDeviceIndexAndRevokesWithoutOverwritingUnrelatedSession() throws Exception {
        Config config = new Config().setClusterName("direct-projection-test-" + UUID.randomUUID());
        config.getNetworkConfig().setPort(15931).setPortAutoIncrement(true);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.event.thread.count", "2");
        config.setProperty("hazelcast.io.input.thread.count", "1");
        config.setProperty("hazelcast.io.output.thread.count", "1");
        config.setProperty("hazelcast.partition.count", "17");
        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var provider = new StaticListableBeanFactory(Map.of("hz", hz)).getBeanProvider(HazelcastInstance.class);
            var store = new HazelcastAuthStateStore(provider, f.properties);
            store.restoreActiveTicketReservations();
            var legacy = new DirectAuthLegacySessionProjection(store, f.cookies);
            var worker = new DirectAuthProjectionService(f.jdbc, f.transactions, f.flows, f.sessions,
                    f.projections, provider, legacy);
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"), tuple,
                    System.currentTimeMillis() + 600_000);
            worker.processPending();
            assertThat(store.activeSessions(42L)).singleElement()
                    .extracting(HazelcastAuthStateStore.AuthSession::sid).isEqualTo(login.flow().activeSessionSid());
            var projected = store.getSession(login.flow().activeSessionSid());
            var canonical = f.sessions.require(login.flow(), f.flows.now(), false);
            assertThat(projected.refreshIdleExpiresAt())
                    .isBeforeOrEqualTo(canonical.idleExpiry().toInstant(java.time.ZoneOffset.UTC));
            assertThat(projected.refreshAbsoluteExpiresAt())
                    .isBeforeOrEqualTo(canonical.absoluteExpiry().toInstant(java.time.ZoneOffset.UTC));
            worker.processPending();
            assertThat(store.activeSessions(42L)).hasSize(1);
            var refreshed = f.refresh.refresh(DirectAuthLoginLogoutTest.request(login.cookies()),
                    new Tuple(tuple.flowId(), 2, UUID.randomUUID().toString()), System.currentTimeMillis() + 600_000);
            worker.processPending();
            assertThat(store.getSession(projected.sid()).generation()).isEqualTo(2);
            assertThat(store.activeSessions(42L)).hasSize(1);
            var activeFlow = refreshed.flow();
            var logout = f.logout.logout(DirectAuthLoginLogoutTest.request(refreshed.cookies()),
                    new Tuple(tuple.flowId(), 3, UUID.randomUUID().toString()));
            worker.processPending();
            assertThat(store.getSession(projected.sid()).status())
                    .isEqualTo(HazelcastAuthStateStore.SessionStatus.REVOKED);
            assertThat(store.activeSessions(42L)).isEmpty();
            f.jdbc.update("UPDATE auth_direct_projection_outbox SET processed_at = NULL");
            worker.processPending();
            assertThat(store.getSession(projected.sid()).status())
                    .isEqualTo(HazelcastAuthStateStore.SessionStatus.REVOKED);
            hz.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2").delete(projected.sid());
            f.jdbc.update("""
                    UPDATE auth_browser_flows SET lifecycle = 'AUTHENTICATED', active_session_sid = ?,
                    flow_epoch = ?, revision = ? WHERE flow_id = ?
                    """, projected.sid(), activeFlow.epoch(), activeFlow.revision(), tuple.flowId());
            f.jdbc.update("UPDATE auth_browser_sessions SET status = 'ACTIVE', flow_epoch = ?", activeFlow.epoch());
            f.jdbc.update("UPDATE auth_direct_session_fences SET status = 'ACTIVE', flow_epoch = ?, revision = ?",
                    activeFlow.epoch(), activeFlow.revision());
            f.jdbc.update("UPDATE auth_direct_projection_outbox SET processed_at = NULL");
            worker.processPending();
            assertThat(store.getSession(projected.sid())).isNull();
            assertThat(hz.<String, DirectAuthProjectionService.Projection>getMap("auth-direct-sessions-v2")
                    .get(projected.sid()).status()).isEqualTo("REVOKED");
            assertThat(f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE processed_at IS NULL",
                    Integer.class)).isPositive();
            var foreign = new HazelcastAuthStateStore.AuthSession(99L, projected.sid(), "foreign",
                    HazelcastAuthStateStore.SessionStatus.ACTIVE, null, null, null, null, null, null, null,
                    null, null, 0, null, null, 1, java.time.Instant.now(), java.time.Instant.now(),
                    java.time.Instant.now().plusSeconds(300), java.time.Instant.now().plusSeconds(600),
                    java.time.Instant.now().plusSeconds(600), null);
            hz.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2").set(projected.sid(), foreign);
            assertThatThrownBy(
                    () -> legacy.project(logout.flow(), f.sessions.find(projected.sid(), false).orElseThrow(),
                            f.flows.now()))
                    .hasMessage("AUTH_STATE_UNAVAILABLE");
            assertThat(store.getSession(projected.sid())).isEqualTo(foreign);
        } finally {
            hz.shutdown();
        }
    }
}
