package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.muse.meomuneum.global.config.JwtProperties;

class RefreshTokenCookieFactoryTest {

    private static final JwtProperties JWT_PROPERTIES = new JwtProperties(
            "project-api", "project-api", "development-only-secret-with-at-least-32-bytes", 3600, 1209600);

    @Test
    void refreshCookieIsSecureWhenEnabled() {
        HttpHeaders headers = new HttpHeaders();

        new RefreshTokenCookieFactory(JWT_PROPERTIES, true).addRefreshTokenCookie(headers, "masked-token");

        assertThat(headers.getFirst(HttpHeaders.SET_COOKIE))
                .contains("Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");
    }

    @Test
    void refreshCookieCanBeUsedByLocalHttpBrowserWhenSecureIsDisabled() {
        HttpHeaders headers = new HttpHeaders();

        new RefreshTokenCookieFactory(JWT_PROPERTIES, false).addRefreshTokenCookie(headers, "masked-token");

        assertThat(headers.getFirst(HttpHeaders.SET_COOKIE))
                .contains("Path=/api/v1/auth", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure");
    }
}
