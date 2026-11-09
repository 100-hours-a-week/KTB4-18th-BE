package com.muse.meomuneum.chat.music;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.music.catalog.RequestableMusicRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.music.queue.enabled", havingValue = "true")
@Import(RequestableMusicRepository.class)
public class ChatMusicQueueConfiguration {
    @Bean
    public ChatMusicQueueService chatMusicQueueService(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMusicStateRepository states, RequestableMusicRepository catalog,
            @Value("${chat.music.keys:}") String keys,
            @Value("${chat.redis.environment}") String environment,
            @Value("${chat.music.queue.capacity:100}") int capacity,
            @Value("${chat.music.queue.command-interval-millis:1000}") int interval) {
        var service = new ChatMusicQueueService(jdbc, participation, states, catalog, new ChatMusicKeys(keys),
                capacity, interval);
        service.validateRetainedKeys(environment);
        return service;
    }
}
