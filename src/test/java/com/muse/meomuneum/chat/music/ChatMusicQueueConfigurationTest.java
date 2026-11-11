package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;

import javax.sql.DataSource;

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
                .withBean(JdbcTemplate.class, this::jdbc)
                .withBean(ChatParticipationService.class, () -> mock(ChatParticipationService.class))
                .withBean(ChatMusicStateRepository.class, () -> mock(ChatMusicStateRepository.class))
                .run(result -> assertThat(result).hasSingleBean(ChatMusicQueueService.class));
    }

    @Test
    void selectedVideoSearchRequiresKeyOnlyWhenExplicitlyEnabled() {
        var enabled = context.withPropertyValues("chat.music.queue.enabled=true", "chat.redis.environment=test",
                "chat.music.keys=fixture:" + Base64.getEncoder().encodeToString(new byte[32]),
                "chat.music.video-search.enabled=true")
                .withBean(JdbcTemplate.class, this::jdbc)
                .withBean(ChatParticipationService.class, () -> mock(ChatParticipationService.class))
                .withBean(ChatMusicStateRepository.class, () -> mock(ChatMusicStateRepository.class));
        enabled.run(result -> assertThat(result).hasFailed());
        enabled.withPropertyValues("chat.music.video-search.api-key=synthetic_secret")
                .run(result -> assertThat(result).hasSingleBean(ChatMusicVideoService.class)
                        .hasSingleBean(ChatMusicQueueService.class));
    }

    private JdbcTemplate jdbc() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        return jdbc;
    }
}
