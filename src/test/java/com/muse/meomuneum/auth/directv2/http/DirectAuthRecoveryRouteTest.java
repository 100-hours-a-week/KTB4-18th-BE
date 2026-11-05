package com.muse.meomuneum.auth.directv2.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRefreshRecoveryController;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthResponseWriter;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFilter;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFlag;
import com.muse.meomuneum.global.security.DirectAuthProtocolMarkerFilter;
import com.muse.meomuneum.global.security.RequestOriginValidationFilter;
import com.muse.meomuneum.global.security.SecurityErrorCode;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;

class DirectAuthRecoveryRouteTest {
    private record Start(String path, Tuple source, Cookie[] binding, Cookie[] proof, Cookie[] current) {
    }

    private Start start(DirectAuthRuntimeFixture f) {
        var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                UUID.randomUUID().toString());
        var login = f.login.login(DirectAuthLoginLogoutTest.request(bootstrap.cookies()),
                new LoginRequest("test@example.invalid", "test-only-password"),
                new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                System.currentTimeMillis() + 600_000);
        var tuple = new Tuple(bootstrap.flow().flowId(), 2, UUID.randomUUID().toString());
        var refreshed = f.refresh.refresh(DirectAuthLoginLogoutTest.request(login.cookies()), tuple,
                System.currentTimeMillis() + 600_000);
        var binding = bootstrap.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).toList();
        var proof = new ArrayList<String>(login.cookies());
        proof.addAll(binding);
        return new Start("/api/v1/auth/token/refresh/operations/" + tuple.operationId() + "/session-recovery",
                tuple, DirectAuthLoginLogoutTest.request(binding).getCookies(),
                DirectAuthLoginLogoutTest.request(proof).getCookies(),
                DirectAuthLoginLogoutTest.request(refreshed.cookies()).getCookies());
    }

    private MockMvc mvc(DirectAuthRuntimeFixture f, boolean admission, boolean verified) {
        var writer = new SecurityErrorResponseWriter(new ObjectMapper());
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-CSRF-TOKEN");
        var csrf = new CsrfFilter(repository);
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrf.setAccessDeniedHandler(
                (request, response, failure) -> writer.write(request, response, SecurityErrorCode.CSRF_DENIED));
        var flag = new DirectAuthAdmissionFlag(
                new MockEnvironment().withProperty("AUTH_DIRECT_V2_ENABLED", Boolean.toString(admission)));
        return MockMvcBuilders.standaloneSetup(new DirectAuthRefreshRecoveryController(new DirectAuthRequestParser(),
                new DirectAuthResponseWriter(), f.sourceRecovery, f.sourceReads)).addFilters(
                        new DirectAuthProtocolMarkerFilter(writer), csrf,
                        new RequestOriginValidationFilter("http://localhost:5173", writer),
                        new DirectAuthAdmissionFilter(flag, writer, f.projections, true, verified))
                .build();
    }

    private MockHttpServletRequestBuilder tuple(MockHttpServletRequestBuilder request, Start start) {
        return request.header("X-Auth-Login-Protocol", "direct-v2")
                .header("X-Auth-Flow-Id", start.source().flowId()).header("X-Auth-Flow-Epoch", "2");
    }

    private MockHttpServletRequestBuilder recovery(Start start, Cookie[] cookies, String id) {
        return tuple(post(start.path()), start).header("Origin", "http://localhost:5173")
                .header("X-CSRF-TOKEN", "test-csrf").cookie(new Cookie("XSRF-TOKEN", "test-csrf"))
                .cookie(cookies).header("X-Auth-Refresh-Recovery-Operation-Id", id)
                .header("X-Auth-Recovery-Client-Expires-At", Long.toString(System.currentTimeMillis() + 400_000));
    }

    @Test
    void bindingMetadataCannotAuthorizeMutationButOriginalReplayProofCanRecoverWithAdmissionOff() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var start = start(f);
            var mvc = mvc(f, false, true);
            var read = mvc.perform(tuple(get(start.path() + "/current"), start).cookie(start.binding())
                    .header("Origin", "http://localhost:5173")).andReturn().getResponse();
            assertThat(read.getStatus()).isEqualTo(200);
            assertThat(read.getContentAsString()).contains("NONE").doesNotContain("access_token");
            String id = UUID.randomUUID().toString();
            var denied = mvc.perform(recovery(start, start.binding(), id)).andReturn().getResponse();
            assertThat(denied.getStatus()).isEqualTo(404);
            assertThat(denied.getHeaders("Set-Cookie")).isEmpty();
            assertThat(f.recoveryRows.find(start.source().flowId(), id, false)).isEmpty();
            var recovered = mvc.perform(recovery(start, start.proof(), id)).andReturn().getResponse();
            assertThat(recovered.getStatus()).isEqualTo(200);
            assertThat(recovered.getHeader("X-Auth-Refresh-Recovery-Operation-Id")).isEqualTo(id);
            assertThat(recovered.getContentAsString()).contains("access_token");
            assertThat(recovered.getHeaders("Set-Cookie")).hasSize(2);
            var status = mvc.perform(tuple(get(start.path() + "/" + id), start).cookie(start.binding())
                    .header("Sec-Fetch-Site", "same-origin")).andReturn().getResponse();
            assertThat(status.getStatus()).isEqualTo(200);
            assertThat(status.getContentAsString()).contains("SUCCEEDED").doesNotContain("access_token");
            assertThat(status.getHeaders("Set-Cookie")).isEmpty();
        }
    }

    @Test
    void originTakesPriorityOverFetchMetadataAndUnverifiedMutationNeverIssuesCredentials() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var start = start(f);
            var mvc = mvc(f, true, false);
            var badOrigin = mvc.perform(tuple(get(start.path() + "/current"), start).cookie(start.binding())
                    .header("Origin", "http://evil.invalid").header("Sec-Fetch-Site", "same-origin"))
                    .andReturn().getResponse();
            assertThat(badOrigin.getStatus()).isEqualTo(403);
            assertThat(badOrigin.getContentAsString()).contains("ORIGIN_NOT_ALLOWED");
            var disabled = mvc.perform(recovery(start, start.proof(), UUID.randomUUID().toString())).andReturn()
                    .getResponse();
            assertThat(disabled.getStatus()).isEqualTo(503);
            assertThat(disabled.getHeaders("Set-Cookie")).isEmpty();
            assertThat(disabled.getContentAsString()).doesNotContain("access_token");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_recovery_operations", Integer.class))
                    .isZero();
        }
    }
    @Test
    void sourceReadsApplyCurrentFlowPriorityAndRejectMultipleFlowsRegardlessOfCookieOrder() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var start = start(f);
            var b = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()), UUID.randomUUID().toString());
            var bFlow = DirectAuthLoginLogoutTest.request(b.cookies().stream()
                    .filter(cookie -> cookie.startsWith("MEO_AUTH_FLOW")).toList()).getCookies();
            var cookies = new ArrayList<Cookie>(java.util.Arrays.asList(start.binding()));
            cookies.addAll(java.util.Arrays.asList(bFlow));
            var mvc = mvc(f, true, true);
            var other = mvc.perform(tuple(get(start.path() + "/current"), start).cookie(cookies.toArray(Cookie[]::new))
                    .header("Origin", "http://localhost:5173")).andReturn().getResponse();
            assertThat(other.getStatus()).isEqualTo(404);
            assertThat(other.getContentAsString()).contains("AUTH_FLOW_NOT_FOUND");
            cookies.addAll(java.util.Arrays.asList(start.current()));
            for (int index = 0; index < 2; index++) {
                var ambiguous = mvc
                        .perform(tuple(get(start.path() + "/current"), start).cookie(cookies.toArray(Cookie[]::new))
                                .header("Origin", "http://localhost:5173"))
                        .andReturn().getResponse();
                assertThat(ambiguous.getStatus()).isEqualTo(404);
                assertThat(ambiguous.getHeaders("Set-Cookie")).isEmpty();
                java.util.Collections.reverse(cookies);
            }
        }
    }

}
