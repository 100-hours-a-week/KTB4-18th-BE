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

class ChatMusicPlaybackConfigurationTest {
    @Test
    void defaultsOffAndRequiresQueueButStartsWithConsistentConfiguration() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        var context = new ApplicationContextRunner().withUserConfiguration(ChatMusicPlaybackConfiguration.class)
                .withPropertyValues("chat.music.keys=fixture:" + Base64.getEncoder().encodeToString(new byte[32]))
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(ChatMusicStateRepository.class, () -> mock(ChatMusicStateRepository.class))
                .withBean(ChatParticipationService.class, () -> mock(ChatParticipationService.class));
        context.run(result -> assertThat(result).doesNotHaveBean(ChatMusicPlaybackService.class));
        context.withPropertyValues("chat.music.playback.enabled=true")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("chat.music.playback.enabled=true")
                .withBean(ChatMusicQueueService.class, () -> mock(ChatMusicQueueService.class))
                .run(result -> assertThat(result).hasSingleBean(ChatMusicPlaybackService.class)
                        .hasSingleBean(ChatMusicPlaybackWorker.class));
    }
}
