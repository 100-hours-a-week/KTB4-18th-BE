package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

class ChatMusicRelayConfigurationTest {
    @Test
    void productionFlagsEnableMusicAndAllowExplicitShutdown() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(loader.load("chat",
                new FileSystemResource("src/chat/resources/application.yaml")).getFirst());
        for (String flag : new String[]{"queue", "playback", "events"}) {
            assertThat(environment.getProperty("chat.music." + flag + ".enabled", Boolean.class)).isFalse();
        }
        environment.getPropertySources().addFirst(loader.load("chat-prod",
                new FileSystemResource("src/chat/resources/application-prod.yaml")).getFirst());
        for (String flag : new String[]{"queue", "playback", "events"}) {
            String property = "chat.music." + flag + ".enabled";
            assertThat(environment.getProperty(property, Boolean.class)).isTrue();
            environment.withProperty("CHAT_MUSIC_" + flag.toUpperCase(java.util.Locale.ROOT) + "_ENABLED", "false");
            assertThat(environment.getProperty(property, Boolean.class)).isFalse();
        }
    }

    @Test
    void defaultsOffAndCannotEnableWithoutThePlaybackAndTransportDependencies() {
        var context = new ApplicationContextRunner().withUserConfiguration(ChatMusicRelayConfiguration.class);
        context.run(result -> assertThat(result).doesNotHaveBean(ChatMusicRelay.class));
        context.withPropertyValues("chat.music.events.enabled=true")
                .run(result -> assertThat(result).hasFailed());
    }
}
