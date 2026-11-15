package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.muse.meomuneum.auth.application.LocalMockLoginService;
import com.muse.meomuneum.auth.application.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.exception.LocalMockLoginForbiddenException;
import com.muse.meomuneum.global.config.JwtProperties;

class LocalMockLoginControllerTest {

    private LocalMockLoginService localMockLoginService;
    private RefreshTokenCookieFactory refreshTokenCookieFactory;
    private LocalMockLoginController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        localMockLoginService = mock(LocalMockLoginService.class);
        refreshTokenCookieFactory = new RefreshTokenCookieFactory(
                new JwtProperties("project-api", "project-api", "development-only-secret-with-at-least-32-bytes",
                        3600, 1209600),
                false);
        controller = new LocalMockLoginController(localMockLoginService, refreshTokenCookieFactory,
                "http://localhost:5175,http://127.0.0.1:5175");
        request = mock(HttpServletRequest.class);
        when(request.getHeader(HttpHeaders.ORIGIN)).thenReturn("http://localhost:5175");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(localMockLoginService.issueToken()).thenReturn(
                new LocalMockLoginService.LocalMockLoginToken("local-token", 3600));
    }

    @Test
    void returnsNormalLoginEnvelopeAndExpiresStaleRefreshCookie() {
        var response = controller.login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=", "Path=/api/v1/auth", "Max-Age=0");
        assertThat(response.getBody().message()).isEqualTo("login success");
        assertThat(response.getBody().data().accessToken()).isEqualTo("local-token");
        assertThat(response.getBody().data().expiresIn()).isEqualTo(3600);
        verify(localMockLoginService).issueToken();
    }

    @Test
    void rejectsUnconfiguredOriginAndNonLoopbackPeerBeforeIssuingToken() {
        when(request.getHeader(HttpHeaders.ORIGIN)).thenReturn("https://attacker.example");

        assertThatThrownBy(() -> controller.login(request)).isInstanceOf(LocalMockLoginForbiddenException.class);

        when(request.getHeader(HttpHeaders.ORIGIN)).thenReturn("http://localhost:5175");
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        assertThatThrownBy(() -> controller.login(request)).isInstanceOf(LocalMockLoginForbiddenException.class);
    }
}
