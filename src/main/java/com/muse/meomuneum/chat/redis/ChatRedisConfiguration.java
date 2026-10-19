package com.muse.meomuneum.chat.redis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chat.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ChatRedisProperties.class)
public class ChatRedisConfiguration {
    @Bean(destroyMethod = "close")
    public ChatRedisConnection chatRedisConnection(ChatRedisProperties properties,
            ObjectProvider<MeterRegistry> registries) {
        ChatRedisConnection connection = new ChatRedisConnection(properties);
        registries.orderedStream().forEach(registry -> Gauge.builder("chat.redis.connected", connection,
                value -> value.isConnected() ? 1 : 0).description("Chat Redis command connection state")
                .register(registry));
        return connection;
    }

    @Bean
    public ChatRedisChannels chatRedisChannels(ChatRedisProperties properties) {
        return new ChatRedisChannels(properties);
    }

    @Bean("chatRedis")
    public HealthIndicator chatRedisHealthIndicator(ChatRedisConnection connection) {
        return () -> {
            try {
                return "PONG".equals(connection.commands().sync().ping()) ? Health.up().build() : Health.down().build();
            } catch (RuntimeException unavailable) {
                return Health.down().build();
            }
        };
    }
}
