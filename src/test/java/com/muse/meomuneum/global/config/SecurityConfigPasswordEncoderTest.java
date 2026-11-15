package com.muse.meomuneum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigPasswordEncoderTest {

    @Test
    void configuredEncoderUsesStrengthEight() {
        var encoder = new SecurityConfig().passwordEncoder(8);
        String encoded = encoder.encode("test-password");

        assertThat(encoded).startsWith("$2a$08$");
        assertThat(encoder.matches("test-password", encoded)).isTrue();
        assertThat(new BCryptPasswordEncoder(10).matches("test-password", encoded)).isTrue();
    }
}
