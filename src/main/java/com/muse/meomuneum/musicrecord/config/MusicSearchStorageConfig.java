package com.muse.meomuneum.musicrecord.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class MusicSearchStorageConfig {
    @Bean
    @DependsOn({"dataSource", "transactionManager"})
    public ThreadPoolTaskExecutor musicSearchStorageExecutor(
            @Value("${music.search-storage.workers:2}") int workers,
            @Value("${music.search-storage.shutdown-await-seconds:120}") int shutdownAwaitSeconds) {
        if (workers <= 0 || shutdownAwaitSeconds <= 0) {
            throw new IllegalArgumentException("music search storage executor settings must be positive");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(0);
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
}
