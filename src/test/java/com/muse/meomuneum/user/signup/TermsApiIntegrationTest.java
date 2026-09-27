package com.muse.meomuneum.user.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TermsApiIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void publicListAndDetailExposeCurrentAndHistoricalTerms() throws Exception {
        long currentAiId = currentAiId();
        mvc.perform(get("/api/v1/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("terms retrieved"))
                .andExpect(jsonPath("$.data.items.length()").value(6));
        mvc.perform(get("/api/v1/terms/{id}", currentAiId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("term retrieved"))
                .andExpect(jsonPath("$.data.terms_id").value(currentAiId))
                .andExpect(jsonPath("$.data.is_required").value(true));
        mvc.perform(get("/api/v1/terms/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("v0.2"));
    }

    @Test
    void signupStoresOnlyAcceptedCurrentVersions() throws Exception {
        long currentAiId = currentAiId();
        String email = "terms-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"password\":\"Testpass1!\","
                + "\"nickname\":\"약관검증\",\"terms_ids\":[1," + currentAiId + "]}";

        mvc.perform(post("/api/v1/users/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("register success"));

        List<Long> accepted = jdbc.queryForList("""
                SELECT a.terms_id FROM terms_agreements a
                JOIN users u ON u.id = a.user_id WHERE u.email = ? ORDER BY a.terms_id
                """, Long.class, email);
        assertThat(accepted).containsExactly(1L, currentAiId);
    }

    @Test
    void oldRequiredVersionUsesExistingBadRequestContract() throws Exception {
        String body = "{\"email\":\"old-terms@example.com\",\"password\":\"Testpass1!\","
                + "\"nickname\":\"약관검증\",\"terms_ids\":[1,2]}";
        mvc.perform(post("/api/v1/users/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request"));
    }

    private long currentAiId() {
        return jdbc.queryForObject("""
                SELECT id FROM terms WHERE type = 'AIPERSONAL'
                ORDER BY effective_at DESC, id DESC LIMIT 1
                """, Long.class);
    }
}
