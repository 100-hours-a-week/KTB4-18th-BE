package com.muse.meomuneum.auth.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "auth.jwt.secret=development-only-secret-with-at-least-32-bytes")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MUSIC_RECORD_LOCAL_TESTS", matches = "true")
class RefreshCsrfProtectionIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        MvcResult csrfIssueResult = mockMvc.perform(get("/api/v1/auth/token/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("csrf token issued"))
                .andReturn();
        JsonNode responseBody = objectMapper.readTree(csrfIssueResult.getResponse().getContentAsByteArray());

        String setCookie = csrfIssueResult.getResponse().getHeader("Set-Cookie");
        org.junit.jupiter.api.Assertions.assertNotNull(setCookie);
        org.junit.jupiter.api.Assertions.assertTrue(setCookie.contains("HttpOnly"));
        org.junit.jupiter.api.Assertions.assertTrue(setCookie.contains("Path=/api/v1/auth"));
        java.util.regex.Matcher csrfCookieMatcher = java.util.regex.Pattern.compile("(?:^|;\\s*)XSRF-TOKEN=([^;]+)")
                .matcher(setCookie);
        org.junit.jupiter.api.Assertions.assertTrue(csrfCookieMatcher.find());
        csrfCookie = new Cookie("XSRF-TOKEN", csrfCookieMatcher.group(1));
        csrfToken = responseBody.path("data").path("csrf_token").asText();
    }

    @Test
    void rejectsRefreshWhenCsrfHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                .cookie(csrfCookie)
                .cookie(new Cookie("refresh_token", "masked-refresh-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("csrf validation failed"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void rejectsRefreshWhenCsrfHeaderDoesNotMatchSessionToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                .cookie(csrfCookie)
                .cookie(new Cookie("refresh_token", "masked-refresh-token"))
                .header("X-CSRF-TOKEN", "mismatched-csrf-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("csrf validation failed"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void passesCsrfValidationThenRejectsMissingRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                .cookie(csrfCookie)
                .header("X-CSRF-TOKEN", csrfToken)
                .header("Origin", "http://localhost:5174"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid refresh token"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
