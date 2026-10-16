package com.muse.meomuneum.recommendation.service;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ImageRecommendationAsyncConfig {
    @Bean("imageRecommendationTaskExecutor")
    public Executor imageRecommendationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("recommendation-image-");
        executor.initialize();
        return executor;
    }
}
