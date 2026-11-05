package com.muse.meomuneum.chat.participation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.distributed-presence.enabled", havingValue = "true")
public class ChatParticipationSchedulingConfig {
    @Bean
    public ThreadPoolTaskScheduler chatServerHeartbeatScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-server-heartbeat-");
        return scheduler;
    }
}
