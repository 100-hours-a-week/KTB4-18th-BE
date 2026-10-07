package com.muse.meomuneum.musicrecord.config;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;

@Configuration
@EnableAsync
public class MusicSearchStorageConfig {
    @Bean
    @DependsOn({"dataSource", "transactionManager"})
    public ThreadPoolTaskExecutor musicSearchStorageExecutor(
            @Value("${music.search-storage.workers:2}") int workers,
            @Value("${music.search-storage.queue-capacity:100}") int queueCapacity,
            @Value("${music.search-storage.shutdown-await-seconds:120}") int shutdownAwaitSeconds) {
        if (workers <= 0 || queueCapacity <= 0 || shutdownAwaitSeconds <= 0) {
            throw new IllegalArgumentException("music search storage executor settings must be positive");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("music-search-storage-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        return executor;
    }

    @Bean
    public ApplicationListener<ContextClosedEvent> musicSearchStorageShutdown(
            @Qualifier("musicSearchStorageExecutor") ThreadPoolTaskExecutor executor,
            ApplicationContext owningContext) {
        return event -> {
            if (event.getApplicationContext() == owningContext) {
                executor.initiateShutdown();
            }
        };
    }

    @Bean
    public RetryTemplate musicSearchStorageRetry(@Value("${music.search-storage.retry-delay:1s}") Duration delay) {
        if (delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("music search storage retry delay must be positive");
        }
        return new RetryTemplate(RetryPolicy.builder().maxRetries(3).delay(delay).multiplier(2)
                .maxDelay(Duration.ofSeconds(4)).predicate(MusicSearchStorageService::isRetryable).build());
    }
}
