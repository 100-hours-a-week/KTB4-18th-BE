package com.muse.meomuneum.chat.control;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.control-events.enabled", havingValue = "true")
public class ChatControlEventsConfiguration {
    @Bean
    public ChatControlEventConsumer chatControlEventConsumer(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatViewLifecycle views, ChatSocketSessions sockets, @Value("${chat.redis.environment}") String environment,
            @Value("${chat.message-relay.enabled:false}") boolean messageRelay,
            ObjectProvider<MeterRegistry> registries) {
        if (!messageRelay) {
            throw new IllegalArgumentException(
                    "Chat control events require the bounded message relay and subscription");
        }
        var consumer = new ChatControlEventConsumer(jdbc, participation, views, sockets, environment);
        registries.orderedStream().forEach(registry -> {
            Gauge.builder("chat.control.events.rejected", consumer, ChatControlEventConsumer::rejectedEvents)
                    .register(registry);
            Gauge.builder("chat.control.events.applied", consumer, ChatControlEventConsumer::appliedEvents)
                    .register(registry);
        });
        return consumer;
    }
}
