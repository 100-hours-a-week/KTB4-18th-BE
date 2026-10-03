package com.muse.meomuneum.user.signup;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAvailabilityDatabaseIntegrationTest {
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final String email = "avail-" + suffix + "@example.com";
    private final String nickname = "가입" + suffix;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestUser() {
        jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test
    void checksExistingAndUnusedValuesAgainstTheDatabase() throws Exception {
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, created_at)
                VALUES (?, ?, ?, 'USER', CURRENT_TIMESTAMP)
                """, email, "unused-test-password-hash", nickname);

        mockMvc.perform(get("/api/v1/users/availability/email").queryParam("value", email.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false));
        mockMvc.perform(get("/api/v1/users/availability/nickname").queryParam("value", nickname))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false));
        mockMvc.perform(get("/api/v1/users/availability/email")
                .queryParam("value", "new-" + suffix + "@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
        mockMvc.perform(get("/api/v1/users/availability/nickname")
                .queryParam("value", "신규" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }
}
