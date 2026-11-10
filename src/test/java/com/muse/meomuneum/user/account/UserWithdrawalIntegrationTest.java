package com.muse.meomuneum.user.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.SessionDecision;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserWithdrawalIntegrationTest {
    private static final String PASSWORD = "test-only-password";
    private static final String PATH = "/api/v1/users/me";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private JwtTokenProvider tokens;
    @Autowired
    private UserAuthenticationService users;
    @Autowired
    private AuthFlowService sessions;

    private final List<Long> testUsers = new ArrayList<>();
    private final List<String> testFlows = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (String flow : testFlows) {
            jdbc.update("DELETE FROM auth_direct_session_fences WHERE flow_id = ?", flow);
            jdbc.update("DELETE FROM auth_direct_projection_outbox WHERE flow_id = ?", flow);
            jdbc.update("DELETE FROM auth_browser_flows WHERE flow_id = ?", flow);
        }
        testUsers.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
    }

    @Test
    void withdrawsAccountAndRevokesEveryDeviceWithoutAffectingAnotherAccount() throws Exception {
        long user = createUser();
        Device first = signIn(user);
        Device second = signIn(user);
        long otherUser = createUser();
        Device other = signIn(otherUser);
        for (Device device : List.of(first, second, other)) {
            mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, device.authorization()))
                    .andExpect(status().isOk());
        }

        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, first.authorization())
                .header(HttpHeaders.ORIGIN, "http://localhost:5173").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(sessions.checkSession(user, first.sid())).isEqualTo(SessionDecision.ACTIVE);
        assertThat(sessions.checkSession(user, second.sid())).isEqualTo(SessionDecision.ACTIVE);

        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, first.authorization())
                .header(HttpHeaders.ORIGIN, "http://localhost:5173").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user))
                .isTrue();
        for (Device device : List.of(first, second)) {
            assertThat(sessions.checkSession(user, device.sid())).isEqualTo(SessionDecision.REJECTED);
            assertThat(jdbc.queryForObject("""
                    SELECT status = 'REVOKED' AND revoked_at IS NOT NULL
                        AND current_refresh_token_hash IS NULL AND encrypted_current_refresh_token IS NULL
                    FROM auth_browser_sessions WHERE sid = ?
                    """, Boolean.class, device.sid())).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM auth_flow_outbox
                    WHERE event_kind = 'SESSION_REVOKED_WITHDRAWAL' AND target_id = ?
                    """, Integer.class, device.sid())).isEqualTo(1);
            mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, device.authorization()))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, other.authorization()))
                .andExpect(status().isOk());
    }

    @Test
    void retainsAccountAndAllSessionsWhenSessionRevocationCannotCommit() throws Exception {
        long user = createUser();
        Device first = signIn(user);
        Device second = signIn(user);
        jdbc.update("UPDATE auth_browser_sessions SET refresh_cookie_mode = 'GENERATION_SCOPED' WHERE sid = ?",
                second.sid());
        jdbc.update("""
                INSERT INTO auth_direct_session_fences
                    (sid, flow_id, flow_epoch, revision, projection_generation, status, updated_at)
                SELECT sid, flow_id, flow_epoch, 2, 1, 'ACTIVE', NOW()
                FROM auth_browser_sessions WHERE sid = ?
                """, second.sid());

        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, first.authorization())
                .header(HttpHeaders.ORIGIN, "http://localhost:5173").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(users.findActiveUser(user).isDeleted()).isFalse();
        assertThat(sessions.checkSession(user, first.sid())).isEqualTo(SessionDecision.ACTIVE);
        assertThat(sessions.checkSession(user, second.sid())).isEqualTo(SessionDecision.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_flow_outbox WHERE target_id IN (?, ?)",
                Integer.class, first.sid(), second.sid())).isZero();
    }

    private long createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 10);
        String email = suffix + "@example.invalid";
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, created_at)
                VALUES (?, ?, ?, 'USER', CURRENT_TIMESTAMP)
                """, email, passwords.encode(PASSWORD), suffix);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        testUsers.add(id);
        return id;
    }

    private Device signIn(long user) {
        String flow = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        testFlows.add(flow);
        jdbc.update("""
                INSERT INTO auth_browser_flows (flow_id, flow_cookie_hash, lifecycle, active_session_sid,
                    cookie_expires_at, created_at, updated_at)
                VALUES (?, SHA2(?, 256), 'AUTHENTICATED', ?, DATE_ADD(NOW(), INTERVAL 1 HOUR), NOW(), NOW())
                """, flow, flow, sid);
        jdbc.update("""
                INSERT INTO auth_browser_sessions (sid, flow_id, flow_epoch, user_id, status, idle_expires_at,
                    absolute_expires_at, current_refresh_token_hash, encrypted_current_refresh_token,
                    created_at, updated_at)
                VALUES (?, ?, 1, ?, 'ACTIVE', DATE_ADD(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
                    SHA2(?, 256), 'test-encrypted-refresh', NOW(), NOW())
                """, sid, flow, user, sid);
        return new Device(sid, "Bearer " + tokens.createAccessToken(users.findActiveUser(user), sid));
    }

    private record Device(String sid, String authorization) {
    }
}
