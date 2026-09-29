package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class RequestOriginValidationFilterTest {

    private static final String ALLOWED = "http://localhost:5173,http://localhost:5174";

    @ParameterizedTest
    @MethodSource("stateChangingMethods")
    void allowsConfiguredOriginForEveryStateChangingMethod(String method) throws Exception {
        var response = apply(method, "http://localhost:5174", null, "/api/v1/music-records");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("stateChangingMethods")
    void allowsRefererOnlyWhenOriginIsAbsent(String method) throws Exception {
        var response = apply(method, null, "http://localhost:5173/music-records/new", "/api/v1/music-records");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsHostileOriginEvenWhenRefererIsAllowed() throws Exception {
        var request = request("POST", "https://attacker.example", "http://localhost:5173/page", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestOriginValidationFilter(ALLOWED, errorResponseWriter()).doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsBlankOriginEvenWhenRefererIsAllowed() throws Exception {
        var request = request("POST", "", "http://localhost:5173/page", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestOriginValidationFilter(ALLOWED, errorResponseWriter()).doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsMissingMalformedAndNullOriginSources() throws Exception {
        for (String origin : List.of("", "null", "https://localhost:5173/path", "http://localhost:9999")) {
            var response = apply("POST", origin.isEmpty() ? null : origin, null, "/api/v1/auth/login");
            assertThat(response.getStatus()).isEqualTo(403);
        }
        var missing = apply("POST", null, null, "/api/v1/auth/login");
        assertThat(missing.getStatus()).isEqualTo(403);
    }

    @Test
    void leavesSafeMethodsAndCorsPreflightUntouched() throws Exception {
        assertThat(apply("GET", null, null, "/api/v1/auth/token/csrf").getStatus()).isEqualTo(200);
        assertThat(apply("OPTIONS", null, null, "/api/v1/auth/token/refresh").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse apply(String method, String origin, String referer, String path)
            throws ServletException, IOException {
        var request = request(method, origin, referer, path);
        var response = new MockHttpServletResponse();
        new RequestOriginValidationFilter(ALLOWED, errorResponseWriter())
                .doFilter(request, response, new MockFilterChain());
        return response;
    }

    private SecurityErrorResponseWriter errorResponseWriter() {
        return new SecurityErrorResponseWriter(new ObjectMapper());
    }

    private MockHttpServletRequest request(String method, String origin, String referer, String path) {
        var request = new MockHttpServletRequest(method, path);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        return request;
    }

    private static Stream<String> stateChangingMethods() {
        return Stream.of("POST", "PUT", "PATCH", "DELETE");
    }
}
