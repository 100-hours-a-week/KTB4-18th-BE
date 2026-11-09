package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatMusicRelayConfigurationTest {
    @Test
    void defaultsOffAndCannotEnableWithoutThePlaybackAndTransportDependencies() {
        var context = new ApplicationContextRunner().withUserConfiguration(ChatMusicRelayConfiguration.class);
        context.run(result -> assertThat(result).doesNotHaveBean(ChatMusicRelay.class));
        context.withPropertyValues("chat.music.events.enabled=true")
                .run(result -> assertThat(result).hasFailed());
    }
}
