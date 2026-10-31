package com.muse.meomuneum.global.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${auth.cors.allowed-origins}") String allowedOrigins) {
        if (Arrays.stream(allowedOrigins.split(",")).map(String::trim).anyMatch("*"::equals)) {
            throw new IllegalArgumentException("Credentialed CORS requires explicit origins");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN",
                "X-Auth-Flow-Id", "X-Auth-Flow-Epoch", "X-Auth-Queue-Correlation", "X-Login-Operation-Id",
                "X-Refresh-Operation-Id", "Idempotency-Key", "X-Queue-Cancel-Operation-Id", "If-Match",
                "X-Chat-View-Session", "X-Auth-Login-Protocol", "Cache-Control",
                "X-Auth-Rebootstrap-Operation-Id", "X-Auth-Refresh-Recovery-Operation-Id",
                "X-Auth-Flow-Operation-Id"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("X-Auth-Flow-Id", "X-Auth-Flow-Epoch", "X-Auth-Flow-Revision",
                "ETag", "Retry-After", "X-Auth-Login-Protocol", "X-Login-Operation-Id",
                "X-Auth-Rebootstrap-Operation-Id", "X-Auth-Refresh-Recovery-Operation-Id",
                "X-Auth-Flow-Operation-Id"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/ws", configuration);
        return source;
    }
}
