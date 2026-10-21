package com.muse.meomuneum.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.CorsFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.LocalMockLoginNoStoreFilter;
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
            @Value("${auth.cors.allowed-origins}") String allowedOrigins,
            @Value("${auth.local-mock-login.enabled:false}") boolean localMockLoginEnabled,
            Environment environment)
            throws Exception {
        String[] csrfIgnoredPaths = {
                "/api/v1/auth/login",
                "/api/v1/auth/login-queue/tickets",
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
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/recommendations/images").authenticated();
                    if (allowGuests) {
                        authorize.requestMatchers(
                                "/api/v1/recommendations",
                                "/api/v1/recommendations/**").permitAll();
                    }
                    authorize.requestMatchers("/api/v1/auth/**", "/ws").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/users/signup").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/users/availability/**").permitAll()
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
        if (localMockLoginEnabled && environment.acceptsProfiles(Profiles.of("local-mock & !prod"))) {
            http.addFilterBefore(new LocalMockLoginNoStoreFilter(), CorsFilter.class);
        }
        return http.build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository(
            @Value("${auth.cookie.secure:true}") boolean secureCookie,
            @Value("${auth.cookie.same-site:Lax}") String sameSite) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookieName("XSRF-TOKEN");
        repository.setCookiePath("/api/v1/auth");
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(secureCookie).sameSite(sameSite));
        return repository;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new RequestAttributeSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder(@Value("${auth.bcrypt.strength:8}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
