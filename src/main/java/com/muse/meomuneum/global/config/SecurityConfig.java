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
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.security.BearerTokenSecurityContextRepository;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RequestOriginValidationFilter;
import com.muse.meomuneum.global.security.SecurityErrorCode;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            JwtTokenProvider jwtTokenProvider, SecurityErrorResponseWriter errorResponseWriter,
            UserAuthenticationService userAuthenticationService,
            @Value("${recommendation.allow-guests:false}") boolean allowGuests,
            @Value("${auth.cors.allowed-origins}") String allowedOrigins)
            throws Exception {
        String[] csrfIgnoredPaths = {
                "/api/v1/auth/login",
                "/api/v1/recommendations",
                "/api/v1/recommendations/**",
                "/api/v1/speech-transcriptions",
                "/api/v1/locations/resolve",
                "/api/v1/chat-rooms/**",
                "/api/v1/users/signup",
                "/api/v1/music-records/**",
                "/api/v1/users/me",
                "/api/v1/users/me/**"
        };

        http.cors(Customizer.withDefaults())
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(csrfIgnoredPaths))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> {
                    if (allowGuests) {
                        authorize.requestMatchers(
                                "/api/v1/recommendations",
                                "/api/v1/recommendations/**").permitAll();
                    }
                    authorize.requestMatchers("/api/v1/auth/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/users/signup").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/terms", "/api/v1/terms/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/map-dots").permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((
                                request, response, authException) -> errorResponseWriter.write(request, response,
                                        SecurityErrorCode.ACCESS_UNAUTHORIZED))
                        .accessDeniedHandler((
                                request, response, accessDeniedException) -> {
                            String path = request.getRequestURI();
                            SecurityErrorCode code = path.startsWith("/api/v1/auth/")
                                    ? SecurityErrorCode.CSRF_DENIED
                                    : path.startsWith("/api/v1/music-records/")
                                            ? SecurityErrorCode.MUSIC_FORBIDDEN
                                            : SecurityErrorCode.ACCESS_DENIED;
                            errorResponseWriter.write(request, response, code);
                        }))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, errorResponseWriter, userAuthenticationService),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new RequestOriginValidationFilter(allowedOrigins, errorResponseWriter),
                        JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        SecurityContextRepository sessionRepository = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
        return new BearerTokenSecurityContextRepository(sessionRepository);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
