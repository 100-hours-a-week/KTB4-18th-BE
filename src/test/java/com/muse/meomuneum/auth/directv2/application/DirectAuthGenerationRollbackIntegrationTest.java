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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.security.DirectAuthProtectedSessionGuard;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFilter;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFlag;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;

class DirectAuthGenerationRollbackIntegrationTest {
    @Test
    void compatibleBinaryWithNewAdmissionOffKeepsProtectionRecoveryLogoutAndProjection() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"), tuple,
                    System.currentTimeMillis() + 600_000);
            var flag = new DirectAuthAdmissionFlag(new MockEnvironment()
                    .withProperty("AUTH_DIRECT_V2_ENABLED", "false"));
            var filter = new DirectAuthAdmissionFilter(flag, new SecurityErrorResponseWriter(new ObjectMapper()),
                    f.projections, false, true);
            var guard = new DirectAuthProtectedSessionGuard(f.jdbc, f.transactions, f.projections);
            String sid = login.flow().activeSessionSid();
            assertThat(guard.check(sid, () -> f.authority.checkSession(42L, sid)))
                    .isEqualTo(AuthFlowService.SessionDecision.ACTIVE);
            var request = DirectAuthLoginLogoutTest.request(bootstrap.cookies());
            request.setMethod("POST");
            request.addHeader("X-Auth-Login-Protocol", "direct-v2");
            request.setRequestURI("/api/v1/auth/login/operations/" + tuple.operationId() + "/session-recovery");
            var recovered = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                recovered.set(f.recovery.recoverLogin(request, tuple).status() == 200);
            });
            assertThat(recovered).isTrue();
            request.setRequestURI("/api/v1/auth/token/refresh");
            var refresh = new MockHttpServletResponse();
            filter.doFilter(request, refresh, (req, res) -> {
                throw new IllegalStateException("ordinary refresh must remain gated");
            });
            assertThat(refresh.getStatus()).isEqualTo(503);
            assertThat(refresh.getHeaders("Set-Cookie")).isEmpty();
            var logout = DirectAuthLoginLogoutTest.request(login.cookies());
            logout.setMethod("POST");
            logout.addHeader("X-Auth-Login-Protocol", "direct-v2");
            logout.setRequestURI("/api/v1/auth/logout");
            filter.doFilter(logout, new MockHttpServletResponse(), (req, res) -> f.logout.logout(logout,
                    new Tuple(tuple.flowId(), 2, UUID.randomUUID().toString())));
            assertThat(guard.check(sid, () -> f.authority.checkSession(42L, sid)))
                    .isEqualTo(AuthFlowService.SessionDecision.REJECTED);
            HazelcastInstance hz = mock(HazelcastInstance.class);
            var lifecycle = mock(LifecycleService.class);
            when(lifecycle.isRunning()).thenReturn(true);
            when(hz.getLifecycleService()).thenReturn(lifecycle);
            IMap<String, DirectAuthProjectionService.Projection> map = mock(IMap.class);
            Map<String, DirectAuthProjectionService.Projection> memory = new ConcurrentHashMap<>();
            when(hz.<String, DirectAuthProjectionService.Projection>getMap("auth-direct-sessions-v2")).thenReturn(map);
            when(map.get(anyString())).thenAnswer(call -> memory.get(call.getArgument(0)));
            when(map.putIfAbsent(anyString(), any()))
                    .thenAnswer(call -> memory.putIfAbsent(call.getArgument(0), call.getArgument(1)));
            when(map.setTtl(anyString(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
            var provider = new StaticListableBeanFactory(Map.of("hz", hz)).getBeanProvider(HazelcastInstance.class);
            new DirectAuthProjectionService(f.jdbc, f.transactions, f.flows, f.sessions, f.projections, provider,
                    mock(DirectAuthLegacySessionProjection.class)).processPending();
            assertThat(memory.get(sid).status()).isEqualTo("REVOKED");
            assertThat(f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE processed_at IS NULL", Integer.class))
                    .isZero();
        }
    }
}
