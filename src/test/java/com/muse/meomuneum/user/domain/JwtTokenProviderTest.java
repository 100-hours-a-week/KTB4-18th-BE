package com.muse.meomuneum.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;

class JwtTokenProviderTest {

    private static final String JWT_SECRET = "development-only-secret-with-at-least-32-bytes";

    @Test
    void createsAndParsesAccessToken() {
        JwtTokenProvider tokenProvider = createTokenProvider();

        TokenClaims claims = tokenProvider.parseAccessToken(tokenProvider.createAccessToken(createUser()));

        assertThat(claims.userId()).isEqualTo(1L);
        assertThat(claims.roles()).containsExactly("USER");
    }

    @Test
    void createsAndParsesRefreshToken() {
        JwtTokenProvider tokenProvider = createTokenProvider();

        Long userId = tokenProvider.parseRefreshToken(tokenProvider.createRefreshToken(createUser()));

        assertThat(userId).isEqualTo(1L);
    }

    private JwtTokenProvider createTokenProvider() {
        JwtProperties properties = new JwtProperties("project-api", "project-api", JWT_SECRET, 3600, 1209600);
        return new JwtTokenProvider(properties);
    }

    private User createUser() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        return user;
    }
}
