package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalMockLoginNoStoreFilterTest {

    private final LocalMockLoginNoStoreFilter filter = new LocalMockLoginNoStoreFilter();

    @Test
    void marksLoginAndCorsPreflightResponsesNoStore() throws Exception {
        for (String method : new String[] { "POST", "OPTIONS" }) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/auth/local-mock-login");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void doesNotChangeNeighboringAuthResponses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cache-Control")).isNull();
    }
}
