package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;

class RefreshTokenSessionServiceTest {

    private final RefreshTokenSessionService service = new RefreshTokenSessionService();

    @Test
    void bindsStableRefreshTokenIdentityToHttpSessionAndAcceptsRepeatedRefresh() {
        MockHttpSession session = new MockHttpSession();
        RefreshTokenClaims claims = claims(1L, session.getId(), "refresh-jti");

        service.register(session, claims);
        service.validate(session, claims);
        service.validate(session, claims);

        assertThat(session.getAttribute(RefreshTokenSessionService.USER_ID_ATTRIBUTE)).isEqualTo(1L);
        assertThat(session.getAttribute(RefreshTokenSessionService.TOKEN_ID_ATTRIBUTE)).isEqualTo("refresh-jti");
        assertThat(session.getMaxInactiveInterval()).isPositive();
    }

    @Test
    void rejectsRefreshFromAnotherSessionOrTokenId() {
        MockHttpSession session = new MockHttpSession();
        RefreshTokenClaims registered = claims(1L, session.getId(), "refresh-jti");
        service.register(session, registered);

        assertThatThrownBy(() -> service.validate(session, claims(1L, "other-session", "refresh-jti")))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> service.validate(session, claims(1L, session.getId(), "other-jti")))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void expiredRefreshCannotBeUsedButAccessLocatorMatchesOnlyItsSession() {
        MockHttpSession session = new MockHttpSession();
        RefreshTokenClaims expired = new RefreshTokenClaims(1L, Instant.now().minusSeconds(1), session.getId(),
                "refresh-jti");
        service.register(session, claims(1L, session.getId(), "refresh-jti"));

        assertThatThrownBy(() -> service.validate(session, expired)).isInstanceOf(AuthenticationFailedException.class);
        assertThat(service.matchesAccessLocator(session,
                new TokenClaims(1L, java.util.List.of("USER"), Instant.now(), session.getId(), "access-jti")))
                .isTrue();
        assertThat(service.matchesAccessLocator(session,
                new TokenClaims(1L, java.util.List.of("USER"), Instant.now(), "other-session", "access-jti")))
                .isFalse();
    }

    private RefreshTokenClaims claims(Long userId, String sessionId, String tokenId) {
        return new RefreshTokenClaims(userId, Instant.now().plusSeconds(3600), sessionId, tokenId);
    }
}
