package com.muse.meomuneum.auth.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class AuthServiceTest {
    @Test
    void centralizedLogoutOnlyExpiresCookieAndDoesNotRevokeBearerSessionAgain() {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        UserAuthenticationService users = mock(UserAuthenticationService.class);
        RefreshTokenCookieFactory refreshCookieFactory = mock(RefreshTokenCookieFactory.class);
        RefreshReplayCipher replayCipher = mock(RefreshReplayCipher.class);
        HazelcastAuthStateStore authStateStore = mock(HazelcastAuthStateStore.class);
        AuthFlowService flowService = mock(AuthFlowService.class);
        AuthStateProperties properties = new AuthStateProperties("01234567890123456789012345678901",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000, 86400, 1, 100000, 300, 45);
        AuthService service = new AuthService(jwt,
                new JwtProperties("project-api", "project-api", "test-secret", 3600, 1209600),
                refreshCookieFactory, users, mock(ApplicationEventPublisher.class), authStateStore, replayCipher,
                properties, flowService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer a-newer-session-token");
        HttpHeaders headers = new HttpHeaders();

        service.logout(request, headers);

        verify(flowService, never()).revokeSession(anyString(), any(), anyString());
        verify(refreshCookieFactory).deleteRefreshTokenCookie(headers);
        verify(jwt, never()).parseAccessTokenForLogout(anyString());
    }
}
