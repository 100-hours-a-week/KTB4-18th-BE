package com.muse.meomuneum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.queue.AuthQueueCookieFactory;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class AuthServiceTest {

    private HazelcastAuthStateStore authStateStore;
    private UserAuthenticationService users;
    private AuthService service;
    private RefreshReplayCipher replayCipher;
    private RefreshTokenCookieFactory refreshCookieFactory;
    private AuthQueueCookieFactory queueCookieFactory;
    private JwtTokenProvider jwt;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("project-api", "project-api", "test-secret", 3600, 1209600);
        authStateStore = mock(HazelcastAuthStateStore.class);
        users = mock(UserAuthenticationService.class);
        AuthStateProperties authStateProperties = new AuthStateProperties("01234567890123456789012345678901",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000, 86400, 1, 100000, 300, 45);
        jwt = mock(JwtTokenProvider.class);
        replayCipher = mock(RefreshReplayCipher.class);
        refreshCookieFactory = mock(RefreshTokenCookieFactory.class);
        queueCookieFactory = mock(AuthQueueCookieFactory.class);
        service = new AuthService(jwt, jwtProperties, refreshCookieFactory, users,
                mock(ApplicationEventPublisher.class), authStateStore, replayCipher,
                queueCookieFactory, authStateProperties);
    }

    @Test
    void loginFailsClosedWhenSharedAuthStateIsUnavailableWithoutAuthenticatingCredentials() {
        when(authStateStore.claimReadyTicket(any(), anyString(), isNull(), eq("identity-hash")))
                .thenThrow(new AuthStateUnavailableException());
        when(authStateStore.loginIdentityHash("user@example.com")).thenReturn("identity-hash");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Login-Operation-Id", UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "password"), request,
                new HttpHeaders())).isInstanceOf(AuthStateUnavailableException.class);

        verify(users, never()).authenticate("user@example.com", "password");
        verify(authStateStore, never()).acquireRequestPermit();
    }

    @Test
    void loginDoesNotAuthenticateCredentialsWhenBcryptPermitIsUnavailable() {
        String operationId = UUID.randomUUID().toString();
        when(authStateStore.loginIdentityHash("user@example.com")).thenReturn("identity-hash");
        when(authStateStore.claimReadyTicket(any(), eq(operationId), isNull(), eq("identity-hash")))
                .thenThrow(new AuthStateTooManyRequestsException("BCRYPT_CAPACITY"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("MEO_QUEUE_V2", "queue-cookie"));
        request.addHeader("X-Login-Operation-Id", operationId);

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "password"), request,
                new HttpHeaders())).isInstanceOf(AuthStateTooManyRequestsException.class)
                .hasMessage("BCRYPT_CAPACITY");

        verify(users, never()).authenticate("user@example.com", "password");
    }

    @Test
    void loginDoesNotStartPasswordVerificationAfterReservedPermitOwnershipIsLost() {
        String operationId = UUID.randomUUID().toString();
        String ticketId = UUID.randomUUID().toString();
        String permitKey = "permit-0";
        Instant now = Instant.now();
        var ticket = new HazelcastAuthStateStore.Ticket(ticketId, 1, now, now.plusSeconds(900),
                now.plusSeconds(60), HazelcastAuthStateStore.TicketStatus.VERIFYING, null,
                "create-operation", operationId, "claim", now.plusSeconds(120), permitKey, null, 1);
        when(authStateStore.loginIdentityHash("user@example.com")).thenReturn("identity-hash");
        when(authStateStore.claimReadyTicket("queue-cookie", operationId, null, "identity-hash"))
                .thenReturn(ticket);
        when(authStateStore.ownsBcryptPermit(ticketId, permitKey)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("MEO_QUEUE_V2", "queue-cookie"));
        request.addHeader("X-Login-Operation-Id", operationId);

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "password"), request,
                new HttpHeaders())).isInstanceOf(AuthStateUnavailableException.class);

        verify(users, never()).authenticate("user@example.com", "password");
        verify(authStateStore).releaseBcryptPermit(ticketId, permitKey);
        verify(authStateStore, never()).acquireRequestPermit();
    }

    @Test
    void invalidCredentialsConsumeReadyTicketAndReleaseItsReservedPermit() {
        String operationId = UUID.randomUUID().toString();
        String ticketId = UUID.randomUUID().toString();
        String permitKey = "permit-0";
        Instant now = Instant.now();
        var ticket = new HazelcastAuthStateStore.Ticket(ticketId, 1, now, now.plusSeconds(900),
                now.plusSeconds(60), HazelcastAuthStateStore.TicketStatus.VERIFYING, null,
                "create-operation", operationId, "claim", now.plusSeconds(120), permitKey, null, 1);
        when(authStateStore.loginIdentityHash("user@example.com")).thenReturn("identity-hash");
        when(authStateStore.claimReadyTicket("queue-cookie", operationId, null, "identity-hash"))
                .thenReturn(ticket);
        when(authStateStore.ownsBcryptPermit(ticketId, permitKey)).thenReturn(true, true);
        when(users.authenticate("user@example.com", "wrong-password"))
                .thenThrow(new AuthenticationFailedException(AuthErrorCode.INVALID_CREDENTIALS));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("MEO_QUEUE_V2", "queue-cookie"));
        request.addHeader("X-Login-Operation-Id", operationId);
        HttpHeaders headers = new HttpHeaders();

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "wrong-password"), request,
                headers)).isInstanceOf(AuthenticationFailedException.class);

        verify(users).authenticate("user@example.com", "wrong-password");
        verify(authStateStore).completeTicket(ticket,
                HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS, null);
        verify(authStateStore, times(2)).ownsBcryptPermit(ticketId, permitKey);
        verify(authStateStore).releaseBcryptPermit(ticketId, permitKey);
        verify(queueCookieFactory).set(headers, "queue-cookie", 60);
        verify(authStateStore, never()).acquireRequestPermit();
    }

    @Test
    void expiredSameOperationReplayWindowDoesNotReturnStoredRefreshToken() {
        Long userId = 17L;
        String sid = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.USER);
        when(users.findActiveUser(userId)).thenReturn(user);
        RefreshTokenClaims oldClaims = new RefreshTokenClaims(userId, now.plusSeconds(3600), sid, "old-jti");
        RefreshTokenClaims nextClaims = new RefreshTokenClaims(userId, now.plusSeconds(3600), sid, "next-jti");
        HazelcastAuthStateStore.AuthSession session = new HazelcastAuthStateStore.AuthSession(userId, sid,
                "Chrome on macOS", HazelcastAuthStateStore.SessionStatus.ACTIVE, "old-jti-hash", "old-token-hash",
                null, null, operationId, now.minusSeconds(1), "encrypted-prior-result", "login-op",
                now.plusSeconds(60), 1, "login-token-hash", "encrypted-login-result", 2, now, now,
                now.plusSeconds(3600), now.plusSeconds(7200), now.plusSeconds(86_400), null);
        when(jwt.parseRefreshToken("old-refresh-token")).thenReturn(oldClaims);
        when(authStateStore.tokenHash("old-refresh-token")).thenReturn("old-token-hash");
        when(authStateStore.jtiHash("old-jti")).thenReturn("old-jti-hash");
        when(authStateStore.getSession(sid)).thenReturn(session);
        when(jwt.createRefreshToken(user, sid)).thenReturn("new-refresh-token");
        when(jwt.parseRefreshToken("new-refresh-token")).thenReturn(nextClaims);
        when(authStateStore.jtiHash("next-jti")).thenReturn("next-jti-hash");
        when(authStateStore.tokenHash("new-refresh-token")).thenReturn("new-token-hash");
        when(jwt.createAccessToken(user, sid)).thenReturn("new-access-token");
        when(jwt.parseAccessToken("new-access-token"))
                .thenReturn(new TokenClaims(userId, List.of("USER"), now.plusSeconds(3600), sid, "access-jti"));
        when(authStateStore.nextRefreshIdleExpiry(any())).thenReturn(now.plusSeconds(7200));
        when(authStateStore.replaceSession(eq(session), any(HazelcastAuthStateStore.AuthSession.class)))
                .thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "old-refresh-token"));
        request.addHeader("X-Refresh-Operation-Id", operationId);
        HttpHeaders headers = new HttpHeaders();

        var response = service.refresh(request, headers);

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        verify(replayCipher, never()).decrypt(anyString());
        verify(refreshCookieFactory).addRefreshTokenCookie(headers, "new-refresh-token");
        verify(authStateStore).replaceSession(eq(session), any(HazelcastAuthStateStore.AuthSession.class));
    }
}
