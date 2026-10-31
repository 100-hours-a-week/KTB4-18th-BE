package com.muse.meomuneum.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CorsConfigTest {
    @Test
    void exposesDirectAndLegacyHeadersWithExplicitCredentialedOrigins() {
        var source = new CorsConfig().corsConfigurationSource("https://example.com");
        var config = source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login"));
        assertThat(config.getAllowCredentials()).isTrue();
        assertThat(config.getAllowedOrigins()).containsExactly("https://example.com");
        assertThat(config.getAllowedHeaders()).containsExactlyInAnyOrder("Authorization", "Content-Type",
                "X-CSRF-TOKEN",
                "X-Auth-Flow-Id", "X-Auth-Flow-Epoch", "X-Auth-Queue-Correlation", "X-Login-Operation-Id",
                "X-Refresh-Operation-Id", "Idempotency-Key", "X-Queue-Cancel-Operation-Id", "If-Match",
                "X-Chat-View-Session", "X-Auth-Login-Protocol", "Cache-Control", "X-Auth-Rebootstrap-Operation-Id",
                "X-Auth-Refresh-Recovery-Operation-Id", "X-Auth-Flow-Operation-Id");
        assertThat(config.getExposedHeaders()).containsExactlyInAnyOrder("X-Auth-Flow-Id", "X-Auth-Flow-Epoch",
                "X-Auth-Flow-Revision", "ETag", "Retry-After", "X-Auth-Login-Protocol", "X-Login-Operation-Id",
                "X-Auth-Rebootstrap-Operation-Id", "X-Auth-Refresh-Recovery-Operation-Id", "X-Auth-Flow-Operation-Id");
        assertThat(config.getExposedHeaders()).doesNotContain("Set-Cookie");
        assertThatThrownBy(() -> new CorsConfig().corsConfigurationSource("*"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
