package com.muse.apps.chat;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.chat.auth.ChatCoreAuthFilter;
import com.muse.meomuneum.chat.auth.ChatCoreSessionVerifier;
import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.global.config.CorsConfig;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.ActiveUserLookup;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RequestOriginValidationFilter;
import com.muse.meomuneum.global.security.SecurityErrorCode;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.global.web.ApplicationBoundaryFilter;
import com.muse.meomuneum.location.config.LocationTokenProperties;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.user.repository.UserRepository;

@Configuration(proxyBeanMethods = false)
@Import({CorsConfig.class, LocationResolutionTokenProvider.class})
@EnableConfigurationProperties({JwtProperties.class, LocationTokenProperties.class})
public class ChatBoundaryConfiguration {
    @Bean
    public Clock locationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ActiveUserLookup activeUserLookup(UserRepository users) {
        return userId -> users.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN));
    }

    @Bean
    public FilterRegistrationBean<ApplicationBoundaryFilter> chatBoundaryFilter(ObjectMapper mapper,
            @Value("${chat.runtime.enabled:false}") boolean enabled) {
        var registration = new FilterRegistrationBean<>(new ApplicationBoundaryFilter(true, () -> enabled, mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean("chatRuntime")
    public HealthIndicator chatRuntimeHealth(@Value("${chat.runtime.enabled:false}") boolean enabled) {
        return () -> enabled ? Health.up().build() : Health.down().build();
    }

    @Bean("participation")
    public HealthIndicator participationHealth(
            ObjectProvider<com.muse.meomuneum.chat.participation.ChatParticipationService> services) {
        return () -> services.getIfAvailable() == null || services.getObject().available()
                ? Health.up().build()
                : Health.down().build();
    }

    @Bean("coreSessionAuth")
    public HealthIndicator coreSessionAuthHealth(ObjectProvider<ChatCoreSessionVerifier> verifiers) {
        return () -> {
            var verifier = verifiers.getIfAvailable();
            return verifier == null || verifier.isAvailable() ? Health.up().build() : Health.down().build();
        };
    }

    @Bean
    public SecurityFilterChain chatSecurity(HttpSecurity http, JwtTokenProvider tokens, ActiveUserLookup users,
            SecurityErrorResponseWriter errors, ObjectProvider<ChatSessionVerifier> verifiers,
            @Value("${auth.cors.allowed-origins}") String origins)
            throws Exception {
        http.cors(Customizer.withDefaults()).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll().requestMatchers("/ws", "/ws/**").permitAll().anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, failure) -> errors.write(request, response,
                                SecurityErrorCode.ACCESS_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, failure) -> errors.write(request, response,
                                SecurityErrorCode.ACCESS_DENIED)))
                .addFilterBefore(new JwtAuthenticationFilter(tokens, errors, users),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RequestOriginValidationFilter(origins, errors), JwtAuthenticationFilter.class);
        var verifier = verifiers.getIfAvailable();
        if (verifier != null) {
            http.addFilterAfter(new ChatCoreAuthFilter(verifier), JwtAuthenticationFilter.class);
        }
        return http.build();
    }
}
