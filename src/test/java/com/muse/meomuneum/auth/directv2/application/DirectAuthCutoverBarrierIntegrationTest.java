package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFilter;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFlag;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;

class DirectAuthCutoverBarrierIntegrationTest {
    @Test
    void recordsOperationalBlockerThatAlreadyAdmittedLegacyWritesCanCommitAfterModeSwitch() throws Exception {
        for (String route : List.of("login", "token/refresh", "logout")) {
            try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users());
                    var pool = Executors.newSingleThreadExecutor()) {
                f.jdbc.update("UPDATE auth_direct_projection_control SET cookie_mode = 'LEGACY'");
                var legacy = new AuthFlowService(f.jdbc, new DataSourceTransactionManager(f.jdbc.getDataSource()),
                        f.properties, false, "Lax", true, 120,
                        new JwtProperties("test", "test", "distinct-test-jwt-key-" + UUID.randomUUID(), 300, 3600));
                var bootstrap = legacy.bootstrap(DirectAuthLoginLogoutTest.request(List.of()), new HttpHeaders());
                var original = DirectAuthLoginLogoutTest.request(List.of("MEO_AUTH_FLOW=" + bootstrap.cookieValue()));
                original.addHeader("X-Auth-Flow-Id", bootstrap.flow().flowId());
                original.addHeader("X-Auth-Flow-Epoch", "1");
                String ticket = UUID.randomUUID().toString();
                f.jdbc.update("UPDATE auth_browser_flows SET lifecycle = 'QUEUED', current_ticket_id = ?", ticket);
                var snapshot = bootstrap.flow();
                if (!"login".equals(route)) {
                    snapshot = legacy.activateSession(original, 42L, UUID.randomUUID().toString(), ticket,
                            "test-refresh-before", f.cipher, new HttpHeaders());
                    original.removeHeader("X-Auth-Flow-Epoch");
                    original.addHeader("X-Auth-Flow-Epoch", Long.toString(snapshot.flowEpoch()));
                }
                var expected = snapshot;
                long before = f.flows.find(snapshot.flowId()).orElseThrow().revision();
                original.setMethod("POST");
                original.setRequestURI("/api/v1/auth/" + route);
                var filter = new DirectAuthAdmissionFilter(new DirectAuthAdmissionFlag(new MockEnvironment()),
                        new SecurityErrorResponseWriter(new ObjectMapper()), f.projections, false, false);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var response = new MockHttpServletResponse();
                var pending = pool.submit(() -> {
                    try {
                        filter.doFilter(original, response, (request, res) -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test barrier timed out");
                                }
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(failure);
                            }
                            var headers = new HttpHeaders();
                            switch (route) {
                                case "login" -> legacy.activateSession(original, 42L, UUID.randomUUID().toString(),
                                        ticket, "test-refresh-after", f.cipher, headers);
                                case "token/refresh" -> legacy.commitRefresh(original, expected,
                                        UUID.randomUUID().toString(), "test-refresh-before", "test-refresh-after",
                                        f.cipher, headers);
                                default -> legacy.commitLogout(original, headers, UUID.randomUUID().toString());
                            }
                            headers.getOrEmpty("Set-Cookie").forEach(value -> response.addHeader("Set-Cookie", value));
                        });
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                f.jdbc.update("UPDATE auth_direct_projection_control SET cookie_mode = 'GENERATION_SCOPED'");
                release.countDown();
                pending.get(5, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(f.flows.find(snapshot.flowId()).orElseThrow().revision()).isGreaterThan(before);
                if ("login".equals(route)) {
                    assertThat(response.getHeaders("Set-Cookie").isEmpty()).isFalse();
                }
                var blocked = new MockHttpServletResponse();
                filter.doFilter(original, blocked, (request, res) -> {
                    throw new IllegalStateException("post-cutover legacy request reached mutation");
                });
                assertThat(blocked.getStatus()).isEqualTo(503);
                assertThat(blocked.getHeaders("Set-Cookie")).isEmpty();
            }
        }
    }
}
