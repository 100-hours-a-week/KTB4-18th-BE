package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class DirectAuthAdmissionFilterTest {
    @Test
    void onlyNewDirectAdmissionsAreDisabled() throws Exception {
        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/auth/flows"}) {
            var response = apply("POST", path, "direct-v2", false);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("LOGIN_PROTOCOL_DISABLED");
            assertThat(apply("POST", path, "direct-v2", true).getStatus()).isEqualTo(200);
        }
        assertThat(apply("POST", "/api/v1/auth/login", null, false).getStatus()).isEqualTo(200);
        assertThat(apply("POST", "/api/v1/auth/token/refresh", "direct-v2", false).getStatus()).isEqualTo(200);
        assertThat(apply("GET", "/api/v1/auth/flow/current-operation", "direct-v2", false).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse apply(String method, String path, String marker, boolean enabled) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (marker != null) {
            request.addHeader("X-Auth-Login-Protocol", marker);
        }
        var response = new MockHttpServletResponse();
        var flag = new DirectAuthAdmissionFlag(new MockEnvironment()
                .withProperty("AUTH_DIRECT_V2_ENABLED", Boolean.toString(enabled)));
        new DirectAuthAdmissionFilter(flag, new SecurityErrorResponseWriter(new ObjectMapper()))
                .doFilter(request, response, new MockFilterChain());
        return response;
    }
}
