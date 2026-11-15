package com.muse.meomuneum.auth.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "core.chat-auth.redis-enabled", havingValue = "true")
@EnableConfigurationProperties(ChatRedisProperties.class)
public class CoreAuthRedisConfiguration {
    @Bean(destroyMethod = "close")
    public CoreAuthRevocationPublisher coreAuthRevocationPublisher(ChatRedisProperties properties,
            @Value("${core.chat-auth.producer-id}") String producer) {
        return new CoreAuthRevocationPublisher(() -> new ChatRedisConnection(properties),
                properties.environment() + ":chat:control:v1", producer);
    }
}
