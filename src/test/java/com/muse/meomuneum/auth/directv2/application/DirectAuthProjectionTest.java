package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthProjectionTest {
    @Test
    void unavailableProjectionRetainsOutboxAndRetryPublishesCanonicalRevocation() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"), tuple,
                    System.currentTimeMillis() + 600_000);
            var empty = new StaticListableBeanFactory().getBeanProvider(HazelcastInstance.class);
            new DirectAuthProjectionService(f.jdbc, f.transactions, f.flows, f.sessions, f.projections, empty,
                    mock(DirectAuthLegacySessionProjection.class))
                    .processPending();
            assertThat(f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE processed_at IS NULL",
                    Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT claim_id FROM auth_direct_projection_outbox", String.class))
                    .isNull();
            HazelcastInstance hz = mock(HazelcastInstance.class);
            LifecycleService lifecycle = mock(LifecycleService.class);
            when(lifecycle.isRunning()).thenReturn(true);
            when(hz.getLifecycleService()).thenReturn(lifecycle);
            IMap<String, DirectAuthProjectionService.Projection> map = mock(IMap.class);
            Map<String, DirectAuthProjectionService.Projection> memory = new ConcurrentHashMap<>();
            when(hz.<String, DirectAuthProjectionService.Projection>getMap("auth-direct-sessions-v2")).thenReturn(map);
            when(map.setTtl(anyString(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
            when(map.get(anyString())).thenAnswer(call -> memory.get(call.getArgument(0)));
            when(map.putIfAbsent(anyString(), any()))
                    .thenAnswer(call -> memory.putIfAbsent(call.getArgument(0), call.getArgument(1)));
            when(map.replace(anyString(), any(), any())).thenAnswer(call -> memory.replace(call.getArgument(0),
                    call.getArgument(1), call.getArgument(2)));
            var provider = new StaticListableBeanFactory(Map.of("hz", hz)).getBeanProvider(HazelcastInstance.class);
            var worker = new DirectAuthProjectionService(f.jdbc, f.transactions, f.flows, f.sessions, f.projections,
                    provider, mock(DirectAuthLegacySessionProjection.class));
            f.jdbc.update("UPDATE auth_direct_projection_outbox SET lease_expires_at = NULL");
            worker.processPending();
            assertThat(memory.values()).singleElement().extracting(DirectAuthProjectionService.Projection::status)
                    .isEqualTo("ACTIVE");
            f.logout.logout(DirectAuthLoginLogoutTest.request(login.cookies()),
                    new Tuple(tuple.flowId(), 2, UUID.randomUUID().toString()));
            worker.processPending();
            assertThat(memory.values()).singleElement().extracting(DirectAuthProjectionService.Projection::status)
                    .isEqualTo("REVOKED");
            assertThat(f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE processed_at IS NULL",
                    Integer.class)).isZero();
            assertThat(f.jdbc.queryForObject("SELECT event_kind FROM auth_flow_outbox", String.class))
                    .isEqualTo("SESSION_REVOKED_LOGOUT");
            f.jdbc.update("UPDATE auth_direct_projection_outbox SET processed_at = NULL");
            worker.processPending();
            assertThat(memory.values()).singleElement().extracting(DirectAuthProjectionService.Projection::status)
                    .isEqualTo("REVOKED");
        }
    }
}
