package com.muse.meomuneum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class AuthSessionServiceCanonicalRefreshTest {

    @Test
    void repairsAnAheadOfDatabaseHazelcastSessionFromTheCanonicalRefreshToken() {
        Instant now = Instant.now();
        Instant idleExpiry = now.plusSeconds(900);
        Instant absoluteExpiry = now.plusSeconds(7200);
        String presentedToken = "canonical-refresh-token";
        String divergentToken = "uncommitted-hazelcast-refresh-token";
        Long userId = 17L;
        String sid = "session-id";
        User user = mock(User.class);
        UserAuthenticationService users = mock(UserAuthenticationService.class);
        HazelcastAuthStateStore stateStore = mock(HazelcastAuthStateStore.class);
        AuthFlowService flowService = mock(AuthFlowService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        RefreshReplayCipher replayCipher = mock(RefreshReplayCipher.class);
        RefreshTokenCookieFactory cookieFactory = mock(RefreshTokenCookieFactory.class);
        AuthStateProperties stateProperties = new AuthStateProperties(
                "01234567890123456789012345678901", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "MEO_QUEUE_V2", 1209600, 7776000, 900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000,
                86400, 1, 100000, 300, 45);
        AuthSessionService service = new AuthSessionService(jwt,
                new JwtProperties("project-api", "project-api", "test-secret", 3600, 1209600), cookieFactory,
                users, mock(ApplicationEventPublisher.class), stateStore, replayCipher, flowService, stateProperties);

        var hazelcastSession = new HazelcastAuthStateStore.AuthSession(userId, sid, "test",
                HazelcastAuthStateStore.SessionStatus.ACTIVE, "jti:divergent", "hash:" + divergentToken,
                "jti:previous", "hash:previous", "failed-operation", now.plusSeconds(60), "encrypted-divergent",
                "login-operation", now.plusSeconds(300), 2, "hash:" + divergentToken, "encrypted-divergent",
                2, now.minusSeconds(60), now.minusSeconds(1), now.plusSeconds(300), idleExpiry, absoluteExpiry,
                null);
        var projection = new AuthFlowService.RefreshSessionProjection(userId, sid, presentedToken,
                idleExpiry, absoluteExpiry);
        String operationId = UUID.randomUUID().toString();
        when(jwt.parseRefreshToken(presentedToken))
                .thenReturn(new RefreshTokenClaims(userId, absoluteExpiry, sid, "canonical-jti"));
        when(jwt.parseRefreshToken("next-refresh-token"))
                .thenReturn(new RefreshTokenClaims(userId, absoluteExpiry, sid, "next-jti"));
        when(jwt.createRefreshToken(user, sid)).thenReturn("next-refresh-token");
        when(jwt.createAccessToken(user, sid)).thenReturn("next-access-token");
        when(jwt.parseAccessToken("next-access-token"))
                .thenReturn(new TokenClaims(userId, List.of("USER"), absoluteExpiry, sid, "access-jti"));
        when(users.findActiveUser(userId)).thenReturn(user);
        when(stateStore.tokenHash(anyString())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        when(stateStore.jtiHash(anyString())).thenAnswer(invocation -> "jti:" + invocation.getArgument(0));
        when(stateStore.getSession(sid)).thenReturn(hazelcastSession);
        when(stateStore.replaceSession(any(), any())).thenReturn(true, true);
        when(stateStore.nextRefreshIdleExpiry(absoluteExpiry)).thenReturn(idleExpiry);
        when(replayCipher.encrypt(anyString())).thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0));
        when(flowService.currentRefreshSession(eq(sid), eq(userId), eq(replayCipher))).thenReturn(projection);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", presentedToken));
        request.addHeader("X-Refresh-Operation-Id", operationId);
        HttpHeaders headers = new HttpHeaders();

        var response = service.refresh(request, headers);

        assertThat(response.accessToken()).isEqualTo("next-access-token");
        var repaired = org.mockito.ArgumentCaptor.forClass(HazelcastAuthStateStore.AuthSession.class);
        verify(stateStore, times(2)).replaceSession(any(), repaired.capture());
        assertThat(repaired.getAllValues().getFirst().currentRefreshHash()).isEqualTo("hash:" + presentedToken);
        assertThat(repaired.getAllValues().getFirst().lastRefreshOperationId()).isNull();
        verify(flowService).recordConsumedRefreshToken(eq(sid), eq("hash:" + presentedToken), eq(absoluteExpiry));
    }
}
