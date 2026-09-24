package com.muse.meomuneum.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.global.security.SecurityErrorCode;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
            JwtTokenProvider jwtTokenProvider, SecurityErrorResponseWriter errorResponseWriter,
            @Value("${recommendation.allow-guests:false}") boolean allowGuests)
            throws Exception {
        String[] csrfIgnoredPaths = {
                "/api/v1/auth/login",
                "/api/v1/auth/logout",
                "/api/v1/recommendations",
                "/api/v1/recommendations/**",
                "/api/v1/speech-transcriptions",
                "/api/v1/locations/resolve",
                "/api/v1/users/signup"
        };

        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(csrfIgnoredPaths))
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .authorizeHttpRequests(authorize -> {
                    if (allowGuests) {
                        authorize.requestMatchers(
                                "/api/v1/recommendations",
                                "/api/v1/recommendations/**"
                        ).permitAll();
                    }
                    authorize.requestMatchers("/api/v1/auth/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/users/signup").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/map-dots").permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((
                                request, response, authException
                        ) -> errorResponseWriter.write(request, response, SecurityErrorCode.ACCESS_UNAUTHORIZED))
                        .accessDeniedHandler((
                                request, response, accessDeniedException
                        ) -> errorResponseWriter.write(request, response, SecurityErrorCode.ACCESS_DENIED)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, errorResponseWriter),
                        UsernamePasswordAuthenticationFilter.class
                );
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
