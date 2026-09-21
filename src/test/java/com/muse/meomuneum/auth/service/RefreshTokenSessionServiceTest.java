package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.security.RefreshTokenClaims;

class RefreshTokenSessionServiceTest {

    private final RefreshTokenSessionService refreshTokenSessionService = new RefreshTokenSessionService();

    @Test
    void registersOnlyRefreshTokenHashInSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RefreshTokenClaims claims = claims(1L);
        String refreshToken = "current-refresh-token";

        refreshTokenSessionService.register(request, claims, refreshToken);

        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session.getAttribute(RefreshTokenSessionService.USER_ID_ATTRIBUTE)).isEqualTo(1L);
        assertThat(session.getAttribute(RefreshTokenSessionService.ACTIVE_TOKEN_HASH_ATTRIBUTE))
                .isNotEqualTo(refreshToken);
        assertThat(session.getAttribute(RefreshTokenSessionService.CONSUMED_TOKEN_HASH_ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(RefreshTokenSessionService.EXPIRES_AT_ATTRIBUTE)).isEqualTo(claims.expiresAt());
        assertThat(session.getMaxInactiveInterval()).isPositive();
    }

    @Test
    void rotatesActiveRefreshTokenAndMarksPreviousTokenAsConsumed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String currentToken = "current-refresh-token";
        String nextToken = "next-refresh-token";
        RefreshTokenClaims currentClaims = claims(1L);
        RefreshTokenClaims nextClaims = claims(1L);
        refreshTokenSessionService.register(request, currentClaims, currentToken);

        refreshTokenSessionService.rotate(request, currentClaims, currentToken, nextClaims, nextToken);

        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session.getAttribute(RefreshTokenSessionService.ACTIVE_TOKEN_HASH_ATTRIBUTE))
                .isNotEqualTo(nextToken)
                .isNotEqualTo(session.getAttribute(RefreshTokenSessionService.CONSUMED_TOKEN_HASH_ATTRIBUTE));
        assertThat(session.getAttribute(RefreshTokenSessionService.CONSUMED_TOKEN_HASH_ATTRIBUTE))
                .isNotEqualTo(currentToken);

        refreshTokenSessionService.rotate(request, nextClaims, nextToken, claims(1L), "third-refresh-token");

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void invalidatesSessionWhenConsumedRefreshTokenIsReused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String currentToken = "current-refresh-token";
        RefreshTokenClaims claims = claims(1L);
        refreshTokenSessionService.register(request, claims, currentToken);
        refreshTokenSessionService.rotate(request, claims, currentToken, claims(1L), "next-refresh-token");
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        assertThatThrownBy(() -> refreshTokenSessionService.rotate(
                request, claims, currentToken, claims(1L), "third-refresh-token"))
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting(exception -> ((AuthenticationFailedException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_INVALID_TOKEN);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void rejectsRefreshTokenWhenSessionDoesNotExist() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RefreshTokenClaims claims = claims(1L);

        assertThatThrownBy(() -> refreshTokenSessionService.rotate(
                request, claims, "current-refresh-token", claims, "next-refresh-token"))
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting(exception -> ((AuthenticationFailedException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_INVALID_TOKEN);
    }

    @Test
    void invalidatesSessionWhenRefreshTokenUserDoesNotMatchSessionUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String currentToken = "current-refresh-token";
        refreshTokenSessionService.register(request, claims(1L), currentToken);
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        assertThatThrownBy(() -> refreshTokenSessionService.rotate(
                request, claims(2L), currentToken, claims(2L), "next-refresh-token"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void allowsOnlyOneConcurrentRotationForSameRefreshToken() throws Exception {
        MockHttpServletRequest registrationRequest = new MockHttpServletRequest();
        String currentToken = "current-refresh-token";
        RefreshTokenClaims currentClaims = claims(1L);
        refreshTokenSessionService.register(registrationRequest, currentClaims, currentToken);
        MockHttpSession session = (MockHttpSession) registrationRequest.getSession(false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> firstResult = executorService.submit(() -> rotateConcurrently(
                    session, currentClaims, currentToken, "next-refresh-token-1", ready, start));
            Future<Boolean> secondResult = executorService.submit(() -> rotateConcurrently(
                    session, currentClaims, currentToken, "next-refresh-token-2", ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(firstResult.get()).isNotEqualTo(secondResult.get());
            assertThat(session.isInvalid()).isTrue();
        } finally {
            executorService.shutdownNow();
        }
    }

    private boolean rotateConcurrently(MockHttpSession session, RefreshTokenClaims currentClaims,
            String currentToken, String nextToken, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException, ExecutionException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        ready.countDown();
        start.await();
        try {
            refreshTokenSessionService.rotate(request, currentClaims, currentToken, claims(1L), nextToken);
            return true;
        } catch (AuthenticationFailedException exception) {
            return false;
        }
    }

    private RefreshTokenClaims claims(Long userId) {
        return new RefreshTokenClaims(userId, Instant.now().plusSeconds(3600));
    }
}
