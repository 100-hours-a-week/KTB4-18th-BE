package com.muse.meomuneum.chat.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;
import com.muse.meomuneum.global.security.AuthRevocationProtocol;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.core-auth.redis-enabled", havingValue = "true")
public class ChatAuthRevocationSubscription {
    private final AtomicLong rejected = new AtomicLong();

    @Bean(destroyMethod = "close")
    public ChatRedisSubscription authRevocationSubscription(ChatRedisConnection connection, ChatRedisChannels channels,
            ChatCoreSessionVerifier verifier, @Value("${chat.core-auth.producer-ids}") String producerIds) {
        Set<String> producers = Arrays.stream(producerIds.split(",")).map(String::trim).collect(Collectors.toSet());
        if (producers.isEmpty() || producers.stream().anyMatch(value -> !AuthRevocationProtocol.validProducer(value))) {
            throw new IllegalArgumentException("Core authentication producer allowlist required");
        }
        return connection.openSubscription(channels, new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String body) {
                try {
                    var event = AuthRevocationProtocol.decode(body, producers);
                    verifier.invalidate(event.userId(), event.sid());
                } catch (RuntimeException invalid) {
                    rejected.incrementAndGet();
                }
            }
        });
    }

    public long rejectedEvents() {
        return rejected.get();
    }
}
