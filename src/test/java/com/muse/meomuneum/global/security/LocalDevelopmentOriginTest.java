package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.connection.ChatWebSocketConfig;
import com.muse.meomuneum.global.config.CorsConfig;

class LocalDevelopmentOriginTest {

    private static final String EXACT_ORIGINS = "http://localhost:5174,https://app.example";

    @ParameterizedTest
    @MethodSource("localOrigins")
    void permitsLocalPortsInCorsAndStateChangingRequests(String origin) throws Exception {
        MockEnvironment environment = environment("test", true);
        CorsConfiguration cors = cors(environment);

        assertThat(cors.checkOrigin(origin)).isEqualTo(origin);
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(apply(environment, origin, null).getStatus()).isEqualTo(200);
        assertThat(apply(environment, null, origin + "/account?view=1").getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost.evil.example:8080", "http://evil-localhost:8080",
            "http://127.0.0.1.evil.example:8080", "https://attacker.example"})
    void rejectsUnconfiguredNonlocalHostsInCorsAndOriginValidation(String origin) throws Exception {
        MockEnvironment environment = environment("test", true);
        assertThat(cors(environment).checkOrigin(origin)).isNull();
        assertThat(apply(environment, origin, "http://localhost:3000/login").getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "file://localhost:3000", "http://user@localhost:3000",
            "http://localhost:3000/path", "http://localhost:3000?query=1", "http://localhost:3000#fragment",
            "http://localhost:3000, http://127.0.0.1:3000", "http://localhost:3000 http://localhost:4000",
            "http://localhost:65536", "http://localhost:", "https://127.0.0.1:"})
    void rejectsMalformedOriginWithoutRefererFallback(String origin) throws Exception {
        assertThat(apply(environment("test", true), origin, "http://localhost:3000/login").getStatus())
                .isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:/account", "https://127.0.0.1:/account?view=1"})
    void rejectsRefererWithAnExplicitEmptyPort(String referer) throws Exception {
        assertThat(apply(environment("test", true), null, referer).getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://[::1]", "http://[::1]:8080"})
    void preservesExplicitIpv6OriginsWithoutMistakingAddressColonsForEmptyPorts(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new RequestOriginValidationFilter(origin, errorWriter()).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsMissingAndMultiplePhysicalOriginHeaders() throws Exception {
        MockEnvironment environment = environment("test", true);
        assertThat(apply(environment, null, null).getStatus()).isEqualTo(403);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("Origin", "http://localhost:4000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(environment, EXACT_ORIGINS).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "test,prod", "prod,test", "dev", ""})
    void ignoresExpansionFlagOutsideTestWithoutProduction(String profiles) throws Exception {
        MockEnvironment environment = environment(profiles, true);
        assertThat(CorsConfig.localOriginPatterns(environment)).isEmpty();
        assertThat(cors(environment).checkOrigin("http://localhost:3000")).isNull();
        assertThat(apply(environment, "http://localhost:3000", null).getStatus()).isEqualTo(403);
        assertThat(apply(environment, "https://app.example", null).getStatus()).isEqualTo(200);
    }

    @Test
    void optOutAndLegacyConstructorKeepExactOriginsAndRejectWildcardConfiguration() throws Exception {
        MockEnvironment environment = environment("test", false);
        assertThat(CorsConfig.localOriginPatterns(environment)).isEmpty();
        assertThat(cors(environment).checkOrigin("http://localhost:3000")).isNull();
        assertThat(apply(environment, "http://localhost:3000", null).getStatus()).isEqualTo(403);
        assertThat(apply(environment, "http://localhost:5174", null).getStatus()).isEqualTo(200);

        for (String allowed : List.of("*", "http://localhost:[*]")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.addHeader("Origin", "http://localhost:3000");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new RequestOriginValidationFilter(allowed, errorWriter()).doFilter(request, response,
                    new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "prod", "test,prod", "prod,test", "dev", ""})
    void websocketRegistrationUsesTheSameProfilePolicy(String profiles) {
        MockEnvironment environment = environment(profiles, true);
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws")).thenReturn(endpoint);
        when(endpoint.setAllowedOrigins(any(String[].class))).thenReturn(endpoint);

        new ChatWebSocketConfig(null, null, null, null, null, EXACT_ORIGINS, null, null, environment)
                .registerStompEndpoints(registry);

        ArgumentCaptor<String[]> patterns = ArgumentCaptor.forClass(String[].class);
        verify(endpoint).setAllowedOriginPatterns(patterns.capture());
        assertThat(patterns.getValue()).containsExactlyElementsOf(CorsConfig.localOriginPatterns(environment));
        verify(endpoint).setAllowedOrigins("http://localhost:5174", "https://app.example");
    }

    private MockHttpServletResponse apply(MockEnvironment environment, String origin, String referer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(environment, EXACT_ORIGINS).doFilter(request, response, chain);
        if (response.getStatus() == 200) {
            assertThat(chain.getRequest()).isNotNull();
        }
        return response;
    }

    private RequestOriginValidationFilter filter(MockEnvironment environment, String allowed) {
        return new RequestOriginValidationFilter(allowed, errorWriter(), CorsConfig.localOriginPatterns(environment));
    }

    private SecurityErrorResponseWriter errorWriter() {
        return new SecurityErrorResponseWriter(new ObjectMapper());
    }

    private CorsConfiguration cors(MockEnvironment environment) {
        return new CorsConfig().corsConfigurationSource(EXACT_ORIGINS, environment)
                .getCorsConfiguration(new MockHttpServletRequest("POST", "/api/v1/auth/login"));
    }

    private MockEnvironment environment(String profiles, boolean enabled) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("auth.cors.local-all-ports-enabled", Boolean.toString(enabled));
        if (!profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        return environment;
    }

    private static Stream<String> localOrigins() {
        return Stream.of("http", "https").flatMap(scheme -> Stream.of("localhost", "127.0.0.1")
                .flatMap(host -> Stream.of("", ":80", ":443", ":1", ":3000", ":5173", ":5174", ":8080", ":65535")
                        .map(port -> scheme + "://" + host + port)));
    }
}
