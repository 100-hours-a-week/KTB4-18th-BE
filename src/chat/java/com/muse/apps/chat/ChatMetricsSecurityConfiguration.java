package com.muse.apps.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Network restriction is also required at the public ALB; this token is never an end-user JWT. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.lifecycle.enabled", havingValue = "true")
public class ChatMetricsSecurityConfiguration {
    @Bean
    @Order(0)
    public SecurityFilterChain chatMetricsSecurity(HttpSecurity http,
            @Value("${chat.lifecycle.metrics-token:}") String token) throws Exception {
        if (!token.isEmpty() && (!token.matches("[A-Za-z0-9._~-]{32,256}"))) {
            throw new IllegalArgumentException("Chat metrics requires a separate 32-256 character service token");
        }
        http.securityMatcher("/actuator/prometheus").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                        .access((authentication, context) -> {
                            String value = context.getRequest().getHeader("Authorization");
                            boolean allowed = !token.isEmpty() && value != null && value.length() <= 263
                                    && MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                                            value.getBytes(StandardCharsets.UTF_8));
                            return new AuthorizationDecision(allowed);
                        }).anyRequest().denyAll());
        return http.build();
    }
}
