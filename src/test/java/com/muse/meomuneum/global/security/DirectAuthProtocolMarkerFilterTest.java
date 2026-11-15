package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class DirectAuthProtocolMarkerFilterTest {
    @Test
    void rejectsMalformedAndDuplicateMarkersWithoutContinuing() throws Exception {
        for (String[] values : new String[][]{{""}, {"unknown"}, {"direct-v2,direct-v2"},
                {"direct-v2", "direct-v2"}}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            for (String value : values) {
                request.addHeader("X-Auth-Login-Protocol", value);
            }
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            new DirectAuthProtocolMarkerFilter(new SecurityErrorResponseWriter(new ObjectMapper()))
                    .doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("INVALID_LOGIN_PROTOCOL");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void acceptsAbsentOrExactMarker() throws Exception {
        for (String marker : new String[]{null, "direct-v2"}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            if (marker != null) {
                request.addHeader("X-Auth-Login-Protocol", marker);
            }
            var chain = new MockFilterChain();
            new DirectAuthProtocolMarkerFilter(new SecurityErrorResponseWriter(new ObjectMapper()))
                    .doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
