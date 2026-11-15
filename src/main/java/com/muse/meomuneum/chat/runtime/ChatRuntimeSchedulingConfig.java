package com.muse.meomuneum.chat.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.lifecycle.enabled", havingValue = "true")
public class ChatRuntimeSchedulingConfig {
    @Bean
    public ThreadPoolTaskScheduler chatRuntimeCheckScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-runtime-check-");
        return scheduler;
    }
}
