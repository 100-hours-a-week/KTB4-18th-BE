package com.muse.meomuneum.auth.directv2.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.CsrfTokenService;
import com.muse.meomuneum.auth.controller.AuthController;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthOperationController;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFilter;
import com.muse.meomuneum.global.security.DirectAuthAdmissionFlag;
import com.muse.meomuneum.global.security.DirectAuthProtocolMarkerFilter;
import com.muse.meomuneum.global.security.RequestOriginValidationFilter;
import com.muse.meomuneum.global.security.SecurityErrorCode;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;

class DirectAuthHybridRoutingIntegrationTest {
    private final AuthService legacy = mock(AuthService.class);
    private final DirectAuthOperationController direct = mock(DirectAuthOperationController.class);
    private final DirectAuthProjectionRepository projections = mock(DirectAuthProjectionRepository.class);

    private MockMvc mvc(boolean admission) {
        var controller = new AuthController(legacy, mock(CsrfTokenService.class));
        ReflectionTestUtils.setField(controller, "directController", direct);
        var writer = new SecurityErrorResponseWriter(new ObjectMapper());
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-CSRF-TOKEN");
        var csrf = new CsrfFilter(repository);
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrf.setRequireCsrfProtectionMatcher(request -> "POST".equals(request.getMethod())
                && !(request.getRequestURI().equals("/api/v1/auth/login")
                        && request.getHeader("X-Auth-Login-Protocol") == null));
        csrf.setAccessDeniedHandler(
                (request, response, failure) -> writer.write(request, response, SecurityErrorCode.CSRF_DENIED));
        var flag = new DirectAuthAdmissionFlag(new MockEnvironment()
                .withProperty("AUTH_DIRECT_V2_ENABLED", Boolean.toString(admission)));
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new DirectAuthProtocolMarkerFilter(writer), csrf,
                        new RequestOriginValidationFilter("http://localhost:5173", writer),
                        new DirectAuthAdmissionFilter(flag, writer, projections))
                .build();
    }

    @Test
    void directLoginExpiryPreflightAllowsOnlyExactConfiguredOrigin() throws Exception {
        var source = new com.muse.meomuneum.global.config.CorsConfig()
                .corsConfigurationSource("http://127.0.0.1:5178");
        var filter = new org.springframework.web.filter.CorsFilter(source);
        var request = new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        request.addHeader("Origin", "http://127.0.0.1:5178");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers",
                "content-type,x-auth-login-protocol,x-auth-flow-id,x-auth-flow-epoch,x-login-operation-id,"
                        + "x-auth-client-expires-at,x-csrf-token");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://127.0.0.1:5178");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).contains("x-auth-client-expires-at");
        request = new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        request.addHeader("Origin", "http://127.0.0.1:5179");
        request.addHeader("Access-Control-Request-Method", "POST");
        response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void legacyLoginRecoveryRouteIsNotRegistered() throws Exception {
        var response = mvc(false).perform(get("/api/v1/auth/login/recovery")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(legacy, direct);
    }

    @Test
    void directCsrfFailureNeverEmitsGeneratedCookie() throws Exception {
        var response = mvc(true).perform(post("/api/v1/auth/login")
                .header("X-Auth-Login-Protocol", "direct-v2")
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"test-only\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).doesNotContain("access_token");
        verifyNoInteractions(legacy, direct);
    }

    @Test
    void ordinaryDirectRefreshIsDisabledWithEitherAdmissionFlagAndNeverFallsBack() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            var response = mvc(enabled).perform(post("/api/v1/auth/token/refresh")
                    .header("X-Auth-Login-Protocol", "direct-v2")
                    .header("Origin", "http://localhost:5173")
                    .header("X-CSRF-TOKEN", "test-csrf").cookie(new Cookie("XSRF-TOKEN", "test-csrf")))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("AUTH_STATE_UNAVAILABLE");
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        }
        verifyNoInteractions(legacy, direct);
    }

    @Test
    void markerlessLoginIsRejectedInEveryModeWithoutLegacyDispatch() throws Exception {
        when(projections.generationMode()).thenReturn(true);
        var response = mvc(true).perform(post("/api/v1/auth/login")
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"test-only\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("INVALID_LOGIN_PROTOCOL");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        verifyNoInteractions(legacy);
        when(projections.generationMode()).thenReturn(false);
        var preserved = mvc(false).perform(post("/api/v1/auth/login")
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"email\":\"test@example.invalid\",\"password\":\"test-only\"}"))
                .andReturn().getResponse();
        assertThat(preserved.getStatus()).isEqualTo(400);
        assertThat(preserved.getContentAsString()).contains("INVALID_LOGIN_PROTOCOL");
        verifyNoInteractions(legacy);
    }
}
