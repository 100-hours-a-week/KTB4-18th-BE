package com.muse.meomuneum.user.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfilePatchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String email;
    private Long userId;
    private Authentication authentication;

    @BeforeEach
    void createUserWithMaleGender() {
        email = "gender-clear-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, nickname, gender, role, created_at)
                VALUES (?, ?, ?, 'MALE', 'USER', CURRENT_TIMESTAMP)
                """, email, "unused-test-password-hash", "성별테스트");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
    }

    @AfterEach
    void removeTestUser() {
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test
    void persistsExplicitNullAndReturnsNullFromSubsequentProfileGet() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                .with(authentication(authentication))
                .header("Origin", "http://localhost:5174")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gender").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void omittedGenderIsPreservedAndExplicitEnumCanReplaceIt() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                .with(authentication(authentication))
                .header("Origin", "http://localhost:5174")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gender").value("MALE"));

        mockMvc.perform(patch("/api/v1/users/me")
                .with(authentication(authentication))
                .header("Origin", "http://localhost:5174")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"FEMALE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gender").value("FEMALE"));
    }

    @Test
    void rejectsUnauthenticatedProfilePatch() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                .header("Origin", "http://localhost:5174")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":null}"))
                .andExpect(status().isUnauthorized());
    }
}
