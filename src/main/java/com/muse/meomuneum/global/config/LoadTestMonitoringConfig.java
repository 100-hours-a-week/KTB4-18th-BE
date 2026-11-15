package com.muse.meomuneum.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("loadtest")
public class LoadTestMonitoringConfig {
    @Bean
    @Order(0)
    public SecurityFilterChain loadTestMonitoringSecurity(HttpSecurity http, Environment environment) throws Exception {
        int managementPort = environment.getProperty("management.server.port", Integer.class, 0);
        int applicationPort = environment.getProperty("server.port", Integer.class, 8080);
        if (managementPort <= 0 || managementPort == applicationPort) {
            throw new IllegalStateException("loadtest monitoring requires a separate management port");
        }
        http.securityMatcher(request -> request.getLocalPort() == managementPort &&
                ("/actuator/health".equals(request.getRequestURI()) ||
                        "/actuator/prometheus".equals(request.getRequestURI())))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
