package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFilter;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFlag;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;

class DirectAuthProjectionSchedulingTest {
    private final Map<String, DirectAuthProjectionService.Projection> memory = new ConcurrentHashMap<>();

    private DirectAuthLoginService.Result login(DirectAuthRuntimeFixture f) {
        var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                UUID.randomUUID().toString());
        return f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                new LoginRequest("test@example.invalid", "test-only-password"),
                new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                System.currentTimeMillis() + 600_000);
    }

    private HazelcastInstance hazelcast(AtomicBoolean available) {
        HazelcastInstance hz = mock(HazelcastInstance.class);
        LifecycleService lifecycle = mock(LifecycleService.class);
        when(lifecycle.isRunning()).thenAnswer(call -> available.get());
        when(hz.getLifecycleService()).thenReturn(lifecycle);
        IMap<String, DirectAuthProjectionService.Projection> map = mock(IMap.class);
        when(hz.<String, DirectAuthProjectionService.Projection>getMap("auth-direct-sessions-v2")).thenReturn(map);
        when(map.setTtl(anyString(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
        when(map.get(anyString())).thenAnswer(call -> memory.get(call.getArgument(0)));
        when(map.putIfAbsent(anyString(), any()))
                .thenAnswer(call -> memory.putIfAbsent(call.getArgument(0), call.getArgument(1)));
        when(map.replace(anyString(), any(), any()))
                .thenAnswer(call -> memory.replace(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        return hz;
    }

    private GenericApplicationContext context(DirectAuthRuntimeFixture f, HazelcastInstance hz,
            DirectAuthLegacySessionProjection legacy) {
        var context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty("auth.direct-v2.enabled", "false")
                .withProperty("AUTH_DIRECT_V2_ENABLED", "false"));
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> f.jdbc);
        context.registerBean(DirectAuthTransactionRunner.class, () -> f.transactions);
        context.registerBean(DirectAuthFlowRepository.class, () -> f.flows);
        context.registerBean(DirectAuthSessionRepository.class, () -> f.sessions);
        context.registerBean(DirectAuthProjectionRepository.class, () -> f.projections);
        context.registerBean(HazelcastInstance.class, () -> hz);
        context.registerBean(DirectAuthLegacySessionProjection.class, () -> legacy);
        new AnnotatedBeanDefinitionReader(context).register(DirectAuthProjectionService.class);
        context.refresh();
        return context;
    }

    @Test
    void admissionOffStillCreatesWorkerAndDeliversLogoutAfterOutageRetry() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var login = login(f);
            var available = new AtomicBoolean(false);
            try (var context = context(f, hazelcast(available), mock(DirectAuthLegacySessionProjection.class))) {
                var request = DirectAuthLoginLogoutTest.request(login.cookies());
                request.setMethod("POST");
                request.setRequestURI("/api/v1/auth/logout");
                request.addHeader("X-Auth-Login-Protocol", "direct-v2");
                var called = new AtomicBoolean();
                new DirectAuthAdmissionFilter(new DirectAuthAdmissionFlag(context.getEnvironment()),
                        new SecurityErrorResponseWriter(new ObjectMapper()), f.projections, false, false)
                        .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                            f.logout.logout(request,
                                    new Tuple(login.flow().flowId(), 2, UUID.randomUUID().toString()));
                            called.set(true);
                        });
                assertThat(called).isTrue();
                var worker = context.getBean(DirectAuthProjectionService.class);
                worker.processPending();
                assertThat(memory).isEmpty();
                assertThat(pending(f, login.flow().activeSessionSid())).isEqualTo(2);
                assertThat(f.jdbc.queryForObject("""
                        SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE claim_id IS NULL
                        AND lease_expires_at > CURRENT_TIMESTAMP(6)
                        """, Integer.class)).isEqualTo(1);
                available.set(true);
                worker.processPending();
                assertThat(memory).isEmpty();
                f.jdbc.update("UPDATE auth_direct_projection_outbox SET lease_expires_at = NULL");
                worker.processPending();
                assertThat(pending(f, login.flow().activeSessionSid())).isZero();
                assertThat(memory.get(login.flow().activeSessionSid()).status()).isEqualTo("REVOKED");
            }
        }
    }

    @Test
    void failedSidBacksOffWithoutBlockingOthersOrBypassingItsEarlierEvent() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var a = login(f);
            var b = login(f);
            String sid = a.flow().activeSessionSid();
            f.logout.logout(DirectAuthLoginLogoutTest.request(a.cookies()),
                    new Tuple(a.flow().flowId(), 2, UUID.randomUUID().toString()));
            var failing = new AtomicBoolean(true);
            var attempts = new AtomicInteger();
            var legacy = mock(DirectAuthLegacySessionProjection.class);
            doAnswer(call -> {
                var session = (DirectAuthSessionRepository.Session) call.getArgument(1);
                if (sid.equals(session.sid())) {
                    attempts.incrementAndGet();
                    assertThat((boolean) call.getArgument(3)).isFalse();
                    if (failing.get()) {
                        throw DirectAuthFailure.unavailable();
                    }
                }
                return null;
            }).when(legacy).project(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
            try (var context = context(f, hazelcast(new AtomicBoolean(true)), legacy)) {
                var worker = context.getBean(DirectAuthProjectionService.class);
                worker.processPending();
                assertThat(pending(f, b.flow().activeSessionSid())).isZero();
                assertThat(pending(f, sid)).isEqualTo(2);
                assertThat(attempts).hasValue(1);
                worker.processPending();
                assertThat(attempts).hasValue(1);
                var c = login(f);
                f.jdbc.update("UPDATE auth_direct_projection_outbox SET lease_expires_at = NULL WHERE sid = ?", sid);
                worker.processPending();
                assertThat(attempts).hasValue(2);
                assertThat(pending(f, c.flow().activeSessionSid())).isZero();
                assertThat(pending(f, sid)).isEqualTo(2);
                failing.set(false);
                f.jdbc.update("UPDATE auth_direct_projection_outbox SET lease_expires_at = NULL WHERE sid = ?", sid);
                worker.processPending();
                assertThat(attempts).hasValue(4);
                assertThat(pending(f, sid)).isZero();
                assertThat(memory.get(sid).status()).isEqualTo("REVOKED");
            }
        }
    }

    private int pending(DirectAuthRuntimeFixture f, String sid) {
        return f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE sid = ? AND processed_at IS NULL",
                Integer.class, sid);
    }
}
