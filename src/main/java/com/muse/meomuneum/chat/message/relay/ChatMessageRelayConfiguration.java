package com.muse.meomuneum.chat.message.relay;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.message.policy.ChatMessageFingerprint;
import com.muse.meomuneum.chat.message.policy.ChatMessagePolicyProperties;
import com.muse.meomuneum.chat.message.service.ChatMessageStorageService;
import com.muse.meomuneum.chat.message.service.DistributedChatMessageService;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.message-relay.enabled", havingValue = "true")
public class ChatMessageRelayConfiguration {
    @Bean
    public DistributedChatMessageService distributedChatMessageService(JdbcTemplate jdbc,
            ChatParticipationService participation, ChatMessageStorageService storage,
            ChatMessagePolicyProperties.Rules rules, @Value("${chat.redis.environment}") String environment,
            @Value("${chat.message-relay.fingerprint-keys:}") String keys) {
        if (!Duration.ofSeconds(1).equals(rules.minimumInterval())) {
            throw new IllegalArgumentException("Distributed Chat requires the agreed one-second send policy");
        }
        return new DistributedChatMessageService(jdbc, participation, storage, new ChatMessageFingerprint(keys),
                environment);
    }

    @Bean
    public ChatMessageOutboxStore chatMessageOutboxStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatParticipationService participation, @Value("${chat.redis.environment}") String environment) {
        return new ChatMessageOutboxStore(jdbc, manager, participation, environment);
    }

    @Bean(destroyMethod = "close")
    public ChatMessageRelay chatMessageRelay(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMessageOutboxStore outbox, DistributedChatMessageService messages, ChatRedisConnection redis,
            ChatRedisChannels channels, ChatPresenceRegistry presence, ChatAuthSessionRegistry auth,
            ChatSessionVerifier verifier, ObjectProvider<SimpMessagingTemplate> broker,
            @Value("${chat.redis.environment}") String environment, ObjectProvider<MeterRegistry> registries) {
        var relay = new ChatMessageRelay(jdbc, participation, outbox, messages, redis, channels, presence, auth,
                verifier, broker, environment);
        registries.orderedStream().forEach(registry -> {
            Gauge.builder("chat.message.relay.ready", relay, value -> value.ready() ? 1 : 0).register(registry);
            Gauge.builder("chat.message.relay.rejected", relay, ChatMessageRelay::rejectedEvents).register(registry);
            Gauge.builder("chat.message.relay.published", relay, ChatMessageRelay::publishedEvents).register(registry);
            Gauge.builder("chat.message.relay.failures", relay, ChatMessageRelay::failures).register(registry);
        });
        return relay;
    }

    @Bean("messageRelay")
    public HealthIndicator messageRelayHealth(ObjectProvider<ChatMessageRelay> relay) {
        return () -> relay.getObject().ready() ? Health.up().build() : Health.down().build();
    }
}
