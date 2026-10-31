package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class RequestOriginValidationFilterDirectV2Test {
    @Test
    void directRequestsRequireOneExactOriginIncludingRecoveryReads() throws Exception {
        for (String method : new String[]{"POST", "GET"}) {
            String path = method.equals("POST")
                    ? "/api/v1/auth/login"
                    : "/api/v1/auth/token/refresh/operations/123/session-recovery/current";
            var request = new MockHttpServletRequest(method, path);
            request.addHeader("X-Auth-Login-Protocol", "direct-v2");
            request.addHeader("Referer", "https://example.com/page");
            var response = new MockHttpServletResponse();
            new RequestOriginValidationFilter("https://example.com",
                    new SecurityErrorResponseWriter(new ObjectMapper()))
                    .doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("ORIGIN_NOT_ALLOWED");
            request.addHeader("Origin", "https://example.com");
            response = new MockHttpServletResponse();
            new RequestOriginValidationFilter("https://example.com",
                    new SecurityErrorResponseWriter(new ObjectMapper()))
                    .doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
            request.addHeader("Origin", "https://example.com");
            response = new MockHttpServletResponse();
            new RequestOriginValidationFilter("https://example.com",
                    new SecurityErrorResponseWriter(new ObjectMapper()))
                    .doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test
    void recoveryReadsAcceptOnlyExactOriginOrOneSameOriginMetadata() throws Exception {
        for (String suffix : new String[]{"current", "recovery"}) {
            String path = "/api/v1/auth/token/refresh/operations/source/session-recovery/" + suffix;
            assertThat(status(path, null, new String[]{"same-origin"})).isEqualTo(200);
            assertThat(status(path, new String[]{"https://example.com"}, null)).isEqualTo(200);
            for (String value : new String[]{"", "null", "https://evil.example",
                    "https://example.com, https://example.com"}) {
                assertThat(status(path, new String[]{value}, new String[]{"same-origin"})).isEqualTo(403);
            }
            assertThat(status(path, new String[]{"https://example.com", "https://example.com"},
                    new String[]{"same-origin"})).isEqualTo(403);
            for (String value : new String[]{"", "same-site", "cross-site", "none", "Same-Origin",
                    "same-origin,same-origin"}) {
                assertThat(status(path, null, new String[]{value})).isEqualTo(403);
            }
            assertThat(status(path, null, null)).isEqualTo(403);
            assertThat(status(path, null, new String[]{"same-origin", "same-origin"})).isEqualTo(403);
        }
    }

    private int status(String path, String[] origins, String[] metadata) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Auth-Login-Protocol", "direct-v2");
        if (origins != null) {
            for (String origin : origins) {
                request.addHeader("Origin", origin);
            }
        }
        if (metadata != null) {
            for (String value : metadata) {
                request.addHeader("Sec-Fetch-Site", value);
            }
        }
        var response = new MockHttpServletResponse();
        new RequestOriginValidationFilter("https://example.com", new SecurityErrorResponseWriter(new ObjectMapper()))
                .doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
