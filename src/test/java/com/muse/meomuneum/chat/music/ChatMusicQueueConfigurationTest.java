package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.participation.ChatParticipationService;

class ChatMusicQueueConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ChatMusicQueueConfiguration.class);

    @Test
    void disabledByDefaultAndCannotEnableWithoutParticipationOrStrongKeys() {
        context.run(result -> assertThat(result).doesNotHaveBean(ChatMusicQueueService.class));
        context.withPropertyValues("chat.music.queue.enabled=true", "chat.redis.environment=test")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("chat.music.queue.enabled=true", "chat.redis.environment=test")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ChatParticipationService.class, () -> mock(ChatParticipationService.class))
                .withBean(ChatMusicStateRepository.class, () -> mock(ChatMusicStateRepository.class))
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("chat.music.queue.enabled=true", "chat.redis.environment=test",
                "chat.music.keys=fixture:" + Base64.getEncoder().encodeToString(new byte[32]))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ChatParticipationService.class, () -> mock(ChatParticipationService.class))
                .withBean(ChatMusicStateRepository.class, () -> mock(ChatMusicStateRepository.class))
                .run(result -> assertThat(result).hasSingleBean(ChatMusicQueueService.class));
    }
}
