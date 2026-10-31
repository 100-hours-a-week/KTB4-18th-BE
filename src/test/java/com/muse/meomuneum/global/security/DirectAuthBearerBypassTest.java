package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.AuthFlowService;

class DirectAuthBearerBypassTest {
    @Test
    void onlyExactProofBasedRoutesBypassBearer() throws Exception {
        String[][] allowed = {{"GET", "/api/v1/auth/flow"}, {"GET", "/api/v1/auth/flow/current-operation"},
                {"GET", "/api/v1/auth/login/operations/id"},
                {"GET", "/api/v1/auth/token/refresh/operations/id/session-recovery/current"},
                {"GET", "/api/v1/auth/token/refresh/operations/id/session-recovery/recovery"},
                {"POST", "/api/v1/auth/flows"}, {"POST", "/api/v1/auth/flow/rebootstrap"},
                {"POST", "/api/v1/auth/login/operations/id/session-recovery"},
                {"POST", "/api/v1/auth/token/refresh/operations/id/session-recovery"}};
        var provider = mock(JwtTokenProvider.class);
        var filter = new JwtAuthenticationFilter(provider, new SecurityErrorResponseWriter(new ObjectMapper()),
                mock(ActiveUserLookup.class));
        for (String[] route : allowed) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            request.addHeader("Authorization", "Basic invalid");
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        verifyNoInteractions(provider);
        for (String[] route : new String[][]{{"GET", "/api/v1/auth/flows"},
                {"POST", "/api/v1/auth/flow/current-operation"},
                {"GET", "/api/v1/auth/login/operations/id/extra"},
                {"GET", "/api/v1/auth/token/refresh/operations/id/session-recovery/current/extra"},
                {"GET", "/api/v1/users/me"}}) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            request.addHeader("Authorization", "Basic invalid");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void protectedRequestsFailClosedWithoutActiveDbAuthority() throws Exception {
        var provider = mock(JwtTokenProvider.class);
        var lookup = mock(ActiveUserLookup.class);
        var claims = new TokenClaims(1L, java.util.List.of("USER"), java.time.Instant.now().plusSeconds(60),
                "session", "token");
        when(provider.parseAccessToken("valid")).thenReturn(claims);
        var authority = mock(AuthFlowService.class);
        for (var decision : AuthFlowService.SessionDecision.values()) {
            when(authority.checkSession(1L, "session")).thenReturn(decision);
            var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            request.addHeader("Authorization", "Bearer valid");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            new JwtAuthenticationFilter(provider, new SecurityErrorResponseWriter(new ObjectMapper()), lookup,
                    authority)
                    .doFilter(request, response, chain);
            if (decision == AuthFlowService.SessionDecision.ACTIVE) {
                assertThat(chain.getRequest()).isNotNull();
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            } else {
                assertThat(chain.getRequest()).isNull();
                assertThat(response.getStatus())
                        .isEqualTo(decision == AuthFlowService.SessionDecision.REJECTED ? 401 : 503);
            }
            SecurityContextHolder.clearContext();
        }
        when(authority.checkSession(1L, "session")).thenReturn(null);
        var nullRequest = new MockHttpServletRequest("GET", "/api/v1/users/me");
        nullRequest.addHeader("Authorization", "Bearer valid");
        var nullResponse = new MockHttpServletResponse();
        new JwtAuthenticationFilter(provider, new SecurityErrorResponseWriter(new ObjectMapper()), lookup, authority)
                .doFilter(nullRequest, nullResponse, new MockFilterChain());
        assertThat(nullResponse.getStatus()).isEqualTo(503);
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer valid");
        var response = new MockHttpServletResponse();
        new JwtAuthenticationFilter(provider, new SecurityErrorResponseWriter(new ObjectMapper()), lookup)
                .doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
