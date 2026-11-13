package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.application.AuthFlowService.SessionDecision;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

public class DirectAuthLoginLogoutTest {
    public static MockHttpServletRequest request(List<String> headers) {
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://localhost:5173");
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(headers.stream().filter(value -> !value.contains("Max-Age=0"))
                .map(value -> value.split(";", 2)[0].split("=", 2))
                .map(value -> new Cookie(value[0], value[1])).toArray(Cookie[]::new));
        return request;
    }

    public static UserAuthenticationService users() {
        var users = mock(UserAuthenticationService.class);
        var user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(user.getRole()).thenReturn(UserRole.USER);
        when(users.authenticate("test@example.invalid", "test-only-password")).thenReturn(user);
        when(users.findActiveUser(42L)).thenReturn(user);
        return users;
    }

    @Test
    void bootstrapLoginReadonlyRecoveryAndLogoutAreCanonicalAndFenced() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(users())) {
            var bootstrapRequest = request(List.of());
            String bootstrapId = UUID.randomUUID().toString();
            var bootstrap = f.flowService.bootstrap(bootstrapRequest, bootstrapId);
            var exact = f.flowService.bootstrap(bootstrapRequest, bootstrapId);
            assertThat(exact.cookies()).isEqualTo(bootstrap.cookies());
            assertThat(exact.data()).isEqualTo(bootstrap.data());
            var original = request(bootstrap.cookies());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            long client = System.currentTimeMillis() + 600_000;
            var result = f.login.login(original, new LoginRequest("test@example.invalid", "test-only-password"), tuple,
                    client);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.flow().lifecycle()).isEqualTo("AUTHENTICATED");
            assertThat(result.flow().epoch()).isEqualTo(2);
            var claims = f.tokens.parseAccessToken((String) ((java.util.Map<?, ?>) result.data()).get("access_token"));
            var current = request(result.cookies());
            long revision = result.flow().revision();
            assertThat(f.flowService.current(current).cookies()).isEmpty();
            var summary = f.recovery.current(current);
            assertThat(summary.cookies()).isEmpty();
            assertThat(((java.util.Map<?, ?>) summary.data().get("operation")).get("clientExpiresAt"))
                    .isEqualTo(Long.toString(client));
            assertThat(f.flows.find(tuple.flowId()).orElseThrow().revision()).isEqualTo(revision);
            assertThat(f.recovery.recoverLogin(current, tuple).status()).isEqualTo(200);
            assertThat(f.recovery.recoverLogin(original, tuple).status()).isEqualTo(200);
            var logoutTuple = new Tuple(tuple.flowId(), 2, UUID.randomUUID().toString());
            var loggedOut = f.logout.logout(current, logoutTuple);
            assertThat(loggedOut.flow().lifecycle()).isEqualTo("ANONYMOUS");
            assertThat(loggedOut.flow().epoch()).isEqualTo(3);
            assertThat(f.sessions.find(claims.sessionId(), false).orElseThrow().status()).isEqualTo("REVOKED");
            assertThat(f.logout.logout(current, logoutTuple).data()).isEqualTo(loggedOut.data());
            assertThat(f.logout.logout(request(loggedOut.cookies()), logoutTuple).data()).isEqualTo(loggedOut.data());
            assertThat(f.flows.find(tuple.flowId()).orElseThrow().epoch()).isEqualTo(3);
            assertThatThrownBy(() -> f.recovery.recoverLogin(current, tuple)).isInstanceOf(DirectAuthFailure.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_flow_outbox", Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_projection_outbox", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void withdrawalRevokesAllDirectSessionsAndQueuesTheirCacheInvalidation() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(users())) {
            var first = signIn(f);
            var second = signIn(f);
            List<String> sids = List.of(first.flow().activeSessionSid(), second.flow().activeSessionSid());

            assertThat(f.authority.revokeAllSessions(42L, "WITHDRAWAL")).containsExactlyInAnyOrderElementsOf(sids);
            assertThat(f.authority.revokeAllSessions(42L, "WITHDRAWAL")).isEmpty();
            for (var device : List.of(first, second)) {
                String sid = device.flow().activeSessionSid();
                assertThat(f.authority.checkSession(42L, sid)).isEqualTo(SessionDecision.REJECTED);
                assertThat(f.sessions.find(sid, false).orElseThrow().refreshHash()).isNull();
                assertThat(f.flows.find(device.flow().flowId()).orElseThrow().lifecycle()).isEqualTo("ANONYMOUS");
                assertThat(f.jdbc.queryForObject("""
                        SELECT status FROM auth_direct_session_fences WHERE sid = ?
                        """, String.class, sid)).isEqualTo("REVOKED");
                assertThat(f.jdbc.queryForObject("""
                        SELECT COUNT(*) FROM auth_direct_projection_outbox WHERE sid = ?
                        """, Integer.class, sid)).isEqualTo(2);
                assertThatThrownBy(() -> f.refresh.refresh(request(device.cookies()),
                        new Tuple(device.flow().flowId(), device.flow().epoch(), UUID.randomUUID().toString()),
                        System.currentTimeMillis() + 600_000)).isInstanceOf(DirectAuthFailure.class);
            }
        }
    }

    @Test
    void loginCannotCreateSessionWhenAccountWasWithdrawnAfterPasswordVerification() throws Exception {
        var users = users();
        doThrow(new AuthenticationFailedException(AuthErrorCode.INVALID_CREDENTIALS))
                .when(users).requireActiveUserForSession(42L);
        try (var f = new DirectAuthRuntimeFixture(users)) {
            var result = signIn(f);
            assertThat(result.status()).isEqualTo(401);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions", Integer.class)).isZero();
        }
    }

    private DirectAuthLoginService.Result signIn(DirectAuthRuntimeFixture f) {
        var bootstrap = f.flowService.bootstrap(request(List.of()), UUID.randomUUID().toString());
        return f.login.login(request(bootstrap.cookies()),
                new LoginRequest("test@example.invalid", "test-only-password"),
                new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                System.currentTimeMillis() + 600_000);
    }

    @Test
    void expiryIsImmutableAndDatabaseMicrosecondsFailAtEquality() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(users())) {
            var now = LocalDateTime.of(2026, 11, 2, 1, 0, 0, 999_999_000);
            long cutoff = now.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + 1;
            assertThat(f.lifecycle.effective(cutoff, now)).isEqualTo(cutoff);
            assertThatThrownBy(() -> f.lifecycle.effective(1, now)).isInstanceOf(DirectAuthFailure.class);
            assertThatThrownBy(() -> f.lifecycle.effective(9_007_199_254_740_991L, now))
                    .isInstanceOf(DirectAuthFailure.class);
            var bootstrap = f.flowService.bootstrap(request(List.of()), UUID.randomUUID().toString());
            var req = request(bootstrap.cookies());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            long client = System.currentTimeMillis() + 600_000;
            f.login.login(req, new LoginRequest("test@example.invalid", "test-only-password"), tuple, client);
            assertThatThrownBy(() -> f.login.login(req,
                    new LoginRequest("test@example.invalid", "test-only-password"), tuple, client + 1))
                    .isInstanceOf(DirectAuthFailure.class).hasMessage("AUTH_OPERATION_ID_REUSED");
            f.jdbc.update("UPDATE auth_login_operations SET effective_expires_at_ms = 1 WHERE flow_id = ?",
                    tuple.flowId());
            assertThatThrownBy(() -> f.recovery.recoverLogin(req, tuple)).isInstanceOf(DirectAuthFailure.class)
                    .hasMessage("REAUTH_REQUIRED");
        }
    }
    @Test
    void canonicalDeviceLimitRejectsEleventhDirectSessionBeforeAuthenticationCommit() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(users())) {
            for (int index = 0; index < 10; index++) {
                var bootstrap = f.flowService.bootstrap(request(List.of()), UUID.randomUUID().toString());
                f.login.login(request(bootstrap.cookies()),
                        new LoginRequest("test@example.invalid", "test-only-password"),
                        new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                        System.currentTimeMillis() + 600_000);
            }
            var bootstrap = f.flowService.bootstrap(request(List.of()), UUID.randomUUID().toString());
            assertThatThrownBy(() -> f.login.login(request(bootstrap.cookies()),
                    new LoginRequest("test@example.invalid", "test-only-password"),
                    new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString()),
                    System.currentTimeMillis() + 600_000)).hasMessage("AUTH_STATE_UNAVAILABLE");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions", Integer.class))
                    .isEqualTo(10);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_login_operations WHERE status = 'SUCCEEDED'",
                    Integer.class)).isEqualTo(10);
        }
    }

}
