package com.muse.meomuneum.chat.connection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ChatHeartbeatConfig {
    @Bean
    public ThreadPoolTaskScheduler chatHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-heartbeat-");
        return scheduler;
    }
}
