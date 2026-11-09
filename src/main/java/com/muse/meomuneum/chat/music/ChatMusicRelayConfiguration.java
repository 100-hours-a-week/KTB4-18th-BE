package com.muse.meomuneum.chat.music;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.message.relay.ChatMessageRelay;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.music.events.enabled", havingValue = "true")
public class ChatMusicRelayConfiguration {
    @Bean
    public ChatMusicOutboxStore chatMusicOutboxStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatParticipationService participation, @Value("${chat.redis.environment}") String environment) {
        return new ChatMusicOutboxStore(jdbc, manager, participation, environment);
    }

    @Bean(destroyMethod = "close")
    @DependsOn("chatMusicPlaybackWorker")
    public ChatMusicRelay chatMusicRelay(ChatParticipationService participation, ChatMusicPlaybackService playback,
            ChatMusicOutboxStore outbox, ChatRedisConnection redis, ChatRedisChannels channels,
            ChatPresenceRegistry presence, ChatAuthSessionRegistry auth, ChatSessionVerifier verifier,
            ChatViewLifecycle views, ObjectProvider<SimpMessagingTemplate> broker,
            @Value("${chat.redis.environment}") String environment, ChatMessageRelay messageRelay,
            ObjectProvider<MeterRegistry> registries) {
        var relay = new ChatMusicRelay(participation, playback, outbox, redis, channels, presence, auth, verifier,
                views, broker, environment);
        registries.orderedStream().forEach(registry -> {
            Gauge.builder("chat.music.relay.ready", relay, value -> value.ready() ? 1 : 0).register(registry);
            Gauge.builder("chat.music.relay.published", relay, ChatMusicRelay::publishedEvents).register(registry);
            Gauge.builder("chat.music.relay.rejected", relay, ChatMusicRelay::rejectedEvents).register(registry);
            Gauge.builder("chat.music.relay.failures", relay, ChatMusicRelay::failures).register(registry);
        });
        return relay;
    }

    @Bean("musicRelay")
    public HealthIndicator musicRelayHealth(ObjectProvider<ChatMusicRelay> relay) {
        return () -> relay.getObject().ready() ? Health.up().build() : Health.down().build();
    }
}
