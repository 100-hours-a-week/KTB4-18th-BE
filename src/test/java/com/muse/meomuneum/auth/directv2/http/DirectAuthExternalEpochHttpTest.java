package com.muse.meomuneum.auth.directv2.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthOperationController;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthResponseWriter;
import com.muse.meomuneum.auth.directv2.security.DirectAuthProtectedSessionGuard;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;

class DirectAuthExternalEpochHttpTest {
    @RestController
    static class Protected {
        @GetMapping("/protected")
        String read() {
            return "ok";
        }
    }

    @Test
    void validAccessRequiresIndependentProductionEpochAndDatabaseReadiness() throws Exception {
        var users = DirectAuthLoginLogoutTest.users();
        try (var f = new DirectAuthRuntimeFixture(users)) {
            f.safetyEnvironment.setActiveProfiles("local");
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"),
                    new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                    System.currentTimeMillis() + 600_000);
            String token = (String) ((java.util.Map<?, ?>) login.data()).get("access_token");
            var guard = new DirectAuthProtectedSessionGuard(f.jdbc, f.transactions, f.projections);
            var controller = new DirectAuthOperationController(new DirectAuthRequestParser(),
                    new DirectAuthResponseWriter(),
                    f.flowService, f.login, f.logout, f.recovery, f.refresh, f.rebootstrap);
            var mvc = MockMvcBuilders.standaloneSetup(new Protected(), controller)
                    .addFilters(new JwtAuthenticationFilter(
                            f.tokens, new SecurityErrorResponseWriter(new ObjectMapper()), users, f.authority, guard))
                    .build();
            assertThat(mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
            f.safetyEnvironment.setActiveProfiles("prod");
            var rejected = mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse();
            assertThat(rejected.getStatus()).isEqualTo(503);
            assertThat(rejected.getHeaders("Set-Cookie")).isEmpty();
            assertThat(rejected.getContentAsString()).doesNotContain("access_token");
            byte[] before = f.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class);
            var mutation = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/auth/flows").header("X-Auth-Flow-Operation-Id", UUID.randomUUID().toString())
                    .header("Origin", "http://localhost:5173")).andReturn().getResponse();
            assertThat(mutation.getStatus()).isEqualTo(503);
            assertThat(mutation.getHeaders("Set-Cookie")).isEmpty();
            assertThat(mutation.getContentAsString()).doesNotContain("access_token");
            assertThat(java.util.Arrays.equals(before,
                    f.jdbc.queryForObject("SELECT next_iv FROM auth_replay_iv_allocators", byte[].class))).isTrue();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_flow_bootstrap_operations", Integer.class))
                    .isEqualTo(1);
            f.safetyEnvironment.withProperty("auth.direct-v2.safety-mode", "PRODUCTION")
                    .withProperty("auth.direct-v2.backend-instances", "1");
            f.jdbc.update("UPDATE auth_direct_projection_control SET readiness = 'PRODUCTION_READY'");
            for (String epoch : List.of("", "0", "01", "-1", "invalid", "9223372036854775808", "2")) {
                f.safetyEnvironment.withProperty("auth.direct-v2.external-restore-epoch", epoch);
                var response = mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                assertThat(response.getHeaders("Set-Cookie")).isEmpty();
                assertThat(response.getContentAsString()).doesNotContain("access_token");
            }
            f.safetyEnvironment.withProperty("auth.direct-v2.external-restore-epoch", "1");
            assertThat(mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
            f.jdbc.update("UPDATE auth_direct_projection_control SET projection_generation = 2");
            assertThat(mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(503);
            f.safetyEnvironment.withProperty("auth.direct-v2.external-restore-epoch", "2");
            assertThat(mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
            f.jdbc.execute("RENAME TABLE auth_direct_projection_control TO test_unavailable_control");
            assertThat(mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(503);
            f.jdbc.update("UPDATE auth_browser_sessions SET refresh_cookie_mode = 'LEGACY'");
            assertThat(mvc.perform(get("/protected").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mvc.perform(get("/protected")).andReturn().getResponse().getStatus()).isEqualTo(200);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
