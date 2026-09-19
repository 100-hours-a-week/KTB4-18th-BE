package com.muse.meomuneum.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.exception.GlobalErrorCode;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
            JwtAuthenticationFilter jwtAuthenticationFilter, SecurityErrorResponseWriter errorResponseWriter)
            throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/token/refresh",
                                "/api/v1/auth/logout"))
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .authorizeHttpRequests(
                        authorize ->
                                authorize.requestMatchers("/api/v1/auth/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((
                                request, response, authException
                        ) -> errorResponseWriter.write(response, GlobalErrorCode.ACCESS_UNAUTHORIZED))
                        .accessDeniedHandler((
                                request, response, accessDeniedException
                        ) -> errorResponseWriter.write(response, GlobalErrorCode.ACCESS_DENIED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
