package com.muse.meomuneum.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class DirectAuthSecurityChainIntegrationTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        createContext(true);
    }

    private void createContext(boolean enabled) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "auth.cors.allowed-origins", "https://allowed.example", "AUTH_DIRECT_V2_ENABLED",
                Boolean.toString(enabled))));
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void markerCsrfAndOriginHaveRequiredPrecedence() throws Exception {
        mvc.perform(login("https://bad.example", "bad", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_LOGIN_PROTOCOL"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(login("https://allowed.example", "bad", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_LOGIN_PROTOCOL"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://allowed.example"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(login("https://bad.example", "direct-v2", false)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_DENIED"));
        mvc.perform(login("https://bad.example", "direct-v2", true)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(login("https://allowed.example", "direct-v2", true)).andExpect(status().isOk());
    }

    @Test
    void preflightIsHandledOnceAndOrdinaryOptionsUsesMarkerAndDeferredOrigin() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://allowed.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://allowed.example"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://bad.example")
                .header("Access-Control-Request-Method", "POST").header("X-Auth-Login-Protocol", "bad"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://bad.example")
                .header("X-Auth-Login-Protocol", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_LOGIN_PROTOCOL"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://bad.example")
                .header("X-Auth-Login-Protocol", "direct-v2"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
        var filters = context.getBean(FilterChainProxy.class).getFilters("/api/v1/auth/login");
        assertThat(filters.stream().filter(f -> f instanceof org.springframework.web.filter.CorsFilter)).hasSize(1);
    }

    @Test
    void duplicateOriginsAndNonPreflightOptionsCannotBypassSecurity() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://allowed.example", "https://allowed.example")
                .header("X-Auth-Login-Protocol", "direct-v2")
                .cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-CSRF-TOKEN", "test-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://allowed.example", "https://allowed.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/auth/login").header("X-Auth-Login-Protocol", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_LOGIN_PROTOCOL"));
        mvc.perform(options("/api/v1/auth/login").header("X-Auth-Login-Protocol", "direct-v2"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "")
                .header("X-Auth-Login-Protocol", "direct-v2"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
    }

    @Test
    void recoveryGetRequiresOriginOrExactSameOriginMetadataAndBypassesBearer() throws Exception {
        for (String suffix : new String[]{"current", "recovery"}) {
            String path = "/api/v1/auth/token/refresh/operations/source/session-recovery/" + suffix;
            mvc.perform(get(path).header("X-Auth-Login-Protocol", "direct-v2")
                    .header("Sec-Fetch-Site", "same-origin").header("Authorization", "Basic invalid"))
                    .andExpect(status().isOk());
            mvc.perform(get(path).header("X-Auth-Login-Protocol", "direct-v2")
                    .header("Sec-Fetch-Site", "same-site"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
            mvc.perform(get(path).header("X-Auth-Login-Protocol", "direct-v2")
                    .header("Origin", "https://bad.example").header("Sec-Fetch-Site", "same-origin"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
        }
        mvc.perform(get("/api/v1/auth/token/refresh/operations/source/session-recovery/current/extra")
                .header("X-Auth-Login-Protocol", "direct-v2").header("Sec-Fetch-Site", "same-origin")
                .header("Authorization", "Basic invalid")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/token/refresh/operations/source/session-recovery")
                .header("X-Auth-Login-Protocol", "direct-v2").header("Sec-Fetch-Site", "same-origin")
                .cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-CSRF-TOKEN", "test-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
    }

    @Test
    void disabledAdmissionsAreCheckedAfterCsrfAndOrigin() throws Exception {
        context.close();
        createContext(false);
        mvc.perform(login("https://allowed.example", "direct-v2", false))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("CSRF_DENIED"));
        mvc.perform(login("https://bad.example", "direct-v2", true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ORIGIN_NOT_ALLOWED"));
        mvc.perform(login("https://allowed.example", "direct-v2", true))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("LOGIN_PROTOCOL_DISABLED"));
        mvc.perform(post("/api/v1/auth/login").header("Referer", "https://allowed.example/page"))
                .andExpect(status().isOk());
    }

    @Test
    void legacyLoginKeepsCsrfExemptionAndRefererFallback() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Referer", "https://allowed.example/page"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/token/refresh").header("Referer", "https://allowed.example/page"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("csrf validation failed"));
    }

    private MockHttpServletRequestBuilder login(String origin, String marker, boolean csrf) {
        var request = post("/api/v1/auth/login").header("Origin", origin).header("X-Auth-Login-Protocol", marker);
        if (csrf) {
            request.cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-CSRF-TOKEN", "test-token");
        }
        return request;
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, Endpoint.class})
    static class TestConfig {
        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            return mock(UserAuthenticationService.class);
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper mapper) {
            return new SecurityErrorResponseWriter(mapper);
        }
    }

    @RestController
    static class Endpoint {
        @RequestMapping("/api/v1/auth/**")
        String endpoint() {
            return "ok";
        }
    }
}
