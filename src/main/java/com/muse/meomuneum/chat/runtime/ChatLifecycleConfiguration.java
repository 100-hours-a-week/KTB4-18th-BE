package com.muse.meomuneum.chat.runtime;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.message.relay.ChatMessageRelay;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.lifecycle.enabled", havingValue = "true")
public class ChatLifecycleConfiguration {
    @Bean
    public ChatRuntimeMetrics chatRuntimeMetrics(MeterRegistry registry) {
        return new ChatRuntimeMetrics(registry);
    }

    @Bean
    public ChatRuntimeLifecycle chatRuntimeLifecycle(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMessageRelay relay, ChatRedisConnection redis, ChatRedisChannels channels, ChatSocketSessions sockets,
            ChatRuntimeMetrics metrics, MeterRegistry registry,
            @Value("${chat.control-events.enabled:false}") boolean controls,
            @Value("${chat.redis.environment}") String environment,
            @Value("${chat.lifecycle.drain-timeout:20s}") Duration timeout,
            @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") Duration phaseTimeout) {
        if (!controls) {
            throw new IllegalArgumentException("Chat lifecycle requires the complete distributed control pipeline");
        }
        if (timeout.compareTo(phaseTimeout) >= 0) {
            throw new IllegalArgumentException("Chat drain must fit within the lifecycle shutdown phase budget");
        }
        var lifecycle = new ChatRuntimeLifecycle(jdbc, participation, redis, channels, sockets, metrics, environment,
                timeout);
        Gauge.builder("chat.connections", sockets, ChatSocketSessions::size).register(registry);
        Gauge.builder("chat.connections.close.failures", sockets, ChatSocketSessions::closeFailures).register(registry);
        Gauge.builder("chat.runtime.accepting", lifecycle, value -> value.permitted() ? 1 : 0).register(registry);
        Gauge.builder("chat.relay.publisher.queued", relay, ChatMessageRelay::publisherQueueSize).register(registry);
        Gauge.builder("chat.relay.receiver.queued", relay, ChatMessageRelay::receiverQueueSize).register(registry);
        return lifecycle;
    }
}
