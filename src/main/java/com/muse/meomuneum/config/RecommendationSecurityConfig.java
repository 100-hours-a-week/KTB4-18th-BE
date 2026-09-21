package com.muse.meomuneum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RecommendationSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain recommendationSecurity(HttpSecurity http,
            @Value("${recommendation.allow-guests:false}") boolean allowGuests) throws Exception {
        http.securityMatcher("/api/v1/recommendations", "/api/v1/recommendations/**",
                "/api/v1/speech-transcriptions");
        if (allowGuests) {
            // dev 프로필의 로컬 개발에만 사용합니다. 운영 기본값은 false입니다.
            http.csrf(csrf -> csrf.disable());
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        }
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"unauthorized\",\"data\":null}");
                }));
        return http.build();
    }

    // 추천 외의 경로를 실수로 공개하지 않습니다. 향후 팀의 인증 설정과 통합하세요.
    @Bean
    @Order(2)
    SecurityFilterChain otherEndpoints(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
    }
}
