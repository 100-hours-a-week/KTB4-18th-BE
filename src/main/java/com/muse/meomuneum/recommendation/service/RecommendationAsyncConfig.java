package com.muse.meomuneum.recommendation.service;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RecommendationAsyncConfig {
    @Bean("recommendationTaskExecutor")
    public Executor recommendationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("recommendation-");
        executor.initialize();
        return executor;
    }
}
