package com.muse.apps.core;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.web.ApplicationBoundaryFilter;

@Configuration(proxyBeanMethods = false)
public class CoreBoundaryConfiguration {
    @Bean
    @Order(0)
    public SecurityFilterChain coreProbeSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(request -> "GET".equals(request.getMethod())
                && ("/actuator/health/liveness".equals(request.getRequestURI())
                        || "/actuator/health/readiness".equals(request.getRequestURI())))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<ApplicationBoundaryFilter> coreBoundaryFilter(ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new ApplicationBoundaryFilter(false, () -> true, mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
