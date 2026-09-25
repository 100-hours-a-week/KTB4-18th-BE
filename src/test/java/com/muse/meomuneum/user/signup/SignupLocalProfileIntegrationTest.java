package com.muse.meomuneum.user.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.user.signup.repository.SignupRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "music-record-signup-local"})
@EnabledIfEnvironmentVariable(named = "MUSIC_SIGNUP_DB_URL", matches = "jdbc:mysql://.*")
class SignupLocalProfileIntegrationTest {
    private final String email = "signup-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private SignupRepository signupRepository;

    @Autowired
    private JwtProperties jwtProperties;

    @AfterEach
    void cleanSyntheticAccount() {
        jdbc.update("DELETE FROM terms_agreements WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
        jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test
    void isolatedSchemaMatchesErdAndPreservesSixInitialTerms() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("meomuneum_music_record_signup_test");
        assertThat(jdbc.queryForList("SELECT id FROM terms ORDER BY id", Long.class))
                .contains(1L, 2L, 3L, 4L, 5L, 6L).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT is_required FROM terms WHERE id = ?", Boolean.class,
                currentAiTermId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM terms WHERE id = ?", String.class,
                currentAiTermId())).isEqualTo("v0.3");
        assertThat(jdbc.queryForObject("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'terms' AND column_name = 'type'
                """, Long.class)).isEqualTo(30L);
        assertThat(jdbc.queryForObject("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'terms' AND column_name = 'version'
                """, Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'terms' AND column_name = 'title'
                """, Long.class)).isEqualTo(100L);
    }

    @Test
    void storesAllSelectedAgreementsAndRejectsDuplicateEmail() throws Exception {
        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1, currentAiTermId(), 3, 4, 5, 6))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("register success"))
                .andExpect(jsonPath("$.data.user_id").isNumber());

        assertThat(jdbc.queryForList("""
                SELECT a.terms_id FROM terms_agreements a
                JOIN users u ON u.id = a.user_id WHERE u.email = ? ORDER BY a.terms_id
                """, Long.class, email)).containsExactly(1L, 3L, 4L, 5L, 6L, (long) currentAiTermId());

        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1, currentAiTermId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("email already exists"));
    }

    @Test
    void rejectsMissingRequiredAgreementWithoutPersistingUser() throws Exception {
        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
                .isZero();
    }

    @Test
    void rejectsMalformedAndRepeatedAgreementIdsAsCommonBadRequest() throws Exception {
        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1, currentAiTermId(), currentAiTermId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request"));
        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1, currentAiTermId())).replace("@example.com", "-invalid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
                .isZero();
    }

    @Test
    void rejectsMissingFirstOrUnknownAgreementId() throws Exception {
        for (List<Integer> ids : List.of(List.of(currentAiTermId()), List.of(1, currentAiTermId(), 99))) {
            mvc.perform(post("/api/v1/users/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody(ids)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("invalid request"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
                .isZero();
    }

    @Test
    void rejectsTokenSignedForPreviousLocalEnvironment() throws Exception {
        assertTrue(jwtProperties.secret().equals(System.getenv("MUSIC_SIGNUP_AUTH_JWT_SECRET")),
                "Signup-local JWT secret was not selected");
        String oldSecret = System.getenv("MUSIC_SIGNUP_PREVIOUS_AUTH_JWT_SECRET");
        if (oldSecret == null || oldSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Previous local JWT secret is unavailable");
        }
        String oldIssuer = System.getenv().getOrDefault("MUSIC_SIGNUP_PREVIOUS_AUTH_JWT_ISSUER", "project-api");
        String oldAudience = System.getenv().getOrDefault("AUTH_JWT_AUDIENCE", "project-api");
        var key = new SecretKeySpec(oldSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(oldIssuer)
                .subject("1")
                .audience(List.of(oldAudience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("type", "ACCESS")
                .claim("roles", List.of("USER"))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        mvc.perform(get("/api/v1/music-records").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rollsBackUserAndFirstAgreementWhenSecondAgreementInsertFails() throws Exception {
        doThrow(new IllegalStateException("synthetic agreement insert failure"))
                .when(signupRepository).createTermsAgreement(anyLong(), eq((long) currentAiTermId()), any());

        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1, currentAiTermId()))))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM terms_agreements a JOIN users u ON u.id = a.user_id WHERE u.email = ?
                """, Integer.class, email)).isZero();
    }

    @Test
    void storesOnlySelectedAgreementsAndCanLogin() throws Exception {
        mvc.perform(post("/api/v1/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(List.of(1, currentAiTermId()))))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForList("""
                SELECT a.terms_id FROM terms_agreements a
                JOIN users u ON u.id = a.user_id WHERE u.email = ? ORDER BY a.terms_id
                """, Long.class, email)).containsExactly(1L, (long) currentAiTermId());

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Testpass1!\"}"))
                .andExpect(status().isOk());
    }

    private String signupBody(List<Integer> ids) {
        String idsJson = ids.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
        return "{\"email\":\"" + email + "\",\"password\":\"Testpass1!\","
                + "\"nickname\":\"가입시험\",\"terms_ids\":[" + idsJson + "]}";
    }

    private int currentAiTermId() {
        return jdbc.queryForObject("""
                SELECT id FROM terms WHERE type = 'AIPERSONAL'
                ORDER BY effective_at DESC, id DESC LIMIT 1
                """, Integer.class);
    }
}
