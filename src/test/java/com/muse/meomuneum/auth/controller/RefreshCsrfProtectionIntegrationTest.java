package com.muse.meomuneum.auth.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "auth.jwt.secret=development-only-secret-with-at-least-32-bytes")
@ActiveProfiles("test")
class RefreshCsrfProtectionIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;
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

        session = (MockHttpSession) csrfIssueResult.getRequest().getSession(false);
        csrfToken = responseBody.path("data").path("csrf_token").asText();
    }

    @Test
    void rejectsRefreshWhenCsrfHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .session(session)
                        .cookie(new Cookie("refresh_token", "masked-refresh-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("request rejected"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void rejectsRefreshWhenCsrfHeaderDoesNotMatchSessionToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .session(session)
                        .cookie(new Cookie("refresh_token", "masked-refresh-token"))
                        .header("X-CSRF-TOKEN", "mismatched-csrf-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("request rejected"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void passesCsrfValidationThenRejectsMissingRefreshSessionState() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid refresh token"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
