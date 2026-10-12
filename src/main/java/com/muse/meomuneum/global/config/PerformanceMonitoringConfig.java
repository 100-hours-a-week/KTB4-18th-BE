package com.muse.meomuneum.global.config;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

/** Internal management listener is not published by the performance Compose environment. */
@Configuration
@Profile("performance")
public class PerformanceMonitoringConfig {
    @Bean
    public MeterBinder performanceExecutorMetrics(@Qualifier("recommendationTaskExecutor") Executor executor) {
        return registry -> {
            if (executor instanceof ThreadPoolTaskExecutor pool) {
                Gauge.builder("performance.recommendation.queue.size", pool,
                        value -> value.getThreadPoolExecutor().getQueue().size()).register(registry);
                Gauge.builder("performance.recommendation.active", pool, ThreadPoolTaskExecutor::getActiveCount)
                        .register(registry);
            }
        };
    }

    @Bean
    @Order(0)
    public SecurityFilterChain performanceMonitoringChain(HttpSecurity http) throws Exception {
        http.securityMatcher(request -> request.getLocalPort() == 9091)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
