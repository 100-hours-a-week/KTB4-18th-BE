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
            ChatMusicVideoService videos,
            @Value("${chat.music.keys:}") String keys,
            @Value("${chat.redis.environment}") String environment,
            @Value("${chat.music.queue.capacity:100}") int capacity,
            @Value("${chat.music.queue.command-interval-millis:1000}") int interval) {
        var service = new ChatMusicQueueService(jdbc, participation, states, catalog, new ChatMusicKeys(keys),
                capacity, interval);
        service.validateRetainedKeys(environment);
        service.setVideoService(videos);
        return service;
    }

    @Bean
    public ChatMusicVideoService chatMusicVideoService(JdbcTemplate jdbc, ChatParticipationService participation,
            RequestableMusicRepository catalog,
            @Value("${chat.music.video-search.enabled:false}") boolean enabled,
            @Value("${chat.music.video-search.api-key:}") String key,
            @Value("${chat.music.video-search.concurrency:2}") int concurrency,
            @Value("${chat.music.video-search.daily-search-budget:75}") int searches,
            @Value("${chat.music.video-search.daily-details-budget:500}") int details,
            @Value("${chat.music.video-search.positive-seconds:3600}") int positive,
            @Value("${chat.music.video-search.empty-seconds:300}") int empty,
            @Value("${chat.music.video-search.verification-seconds:300}") int verification) {
        var budget = new ChatMusicVideoBudget(jdbc);
        var provider = enabled ? new YouTubeMusicVideoProvider(key, budget, concurrency, searches, details) : null;
        return new ChatMusicVideoService(jdbc, participation, catalog, provider, budget, positive, empty, verification);
    }
}
