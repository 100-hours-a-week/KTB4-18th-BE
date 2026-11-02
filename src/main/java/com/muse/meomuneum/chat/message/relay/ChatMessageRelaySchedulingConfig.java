package com.muse.meomuneum.chat.message.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.message-relay.enabled", havingValue = "true")
public class ChatMessageRelaySchedulingConfig {
    @Bean
    public ThreadPoolTaskScheduler chatRelayDispatchScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-relay-dispatch-");
        return scheduler;
    }
}
