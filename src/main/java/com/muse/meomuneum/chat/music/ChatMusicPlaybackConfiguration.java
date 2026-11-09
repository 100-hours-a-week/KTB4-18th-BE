package com.muse.meomuneum.chat.music;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.participation.ChatParticipationService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.music.playback.enabled", havingValue = "true")
public class ChatMusicPlaybackConfiguration {
    @Bean
    public ChatMusicPlaybackService chatMusicPlaybackService(JdbcTemplate jdbc, ChatMusicStateRepository states,
            @Value("${chat.music.keys:}") String keys,
            @Value("${chat.music.queue.command-interval-millis:1000}") int interval) {
        if (interval < 0 || interval > 60_000) {
            throw new IllegalArgumentException("Invalid music command interval");
        }
        var service = new ChatMusicPlaybackService(jdbc, states,
                new ChatMusicCommandStore(jdbc, new ChatMusicKeys(keys), interval));
        service.validateStorage();
        return service;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ChatMusicPlaybackWorker chatMusicPlaybackWorker(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMusicQueueService queue, ChatMusicPlaybackService playback) {
        queue.setPlaybackService(playback);
        return new ChatMusicPlaybackWorker(jdbc, participation, playback);
    }
}
