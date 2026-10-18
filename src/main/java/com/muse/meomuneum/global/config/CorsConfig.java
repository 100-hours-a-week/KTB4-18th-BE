package com.muse.meomuneum.global.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${auth.cors.allowed-origins}") String allowedOrigins, Environment environment) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        configuration.setAllowedOriginPatterns(localOriginPatterns(environment));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN",
                "X-Refresh-Operation-Id", "X-Login-Operation-Id", "Idempotency-Key", "X-Chat-View-Session"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Retry-After"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/ws", configuration);
        return source;
    }
    public static List<String> localOriginPatterns(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("(dev | test) & !prod"))
                || !environment.getProperty("auth.cors.local-all-ports-enabled", Boolean.class, false)) {
            return List.of();
        }
        return List.of("http://localhost:[*]", "http://127.0.0.1:[*]",
                "https://localhost:[*]", "https://127.0.0.1:[*]");
    }
}
