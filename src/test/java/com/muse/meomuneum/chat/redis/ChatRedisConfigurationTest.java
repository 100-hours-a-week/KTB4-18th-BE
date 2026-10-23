package com.muse.meomuneum.chat.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatRedisConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatRedisConfiguration.class);

    @Test
    void disabledByDefaultDoesNotCreateConnectionsOrRequireSecrets() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ChatRedisConnection.class)
                    .doesNotHaveBean(ChatRedisChannels.class).doesNotHaveBean("chatRedis")
                    .doesNotHaveBean("chatRedisSubscriptions");
        });
    }

    @Test
    void invalidEnabledConfigurationFailsBeforeOpeningConnection() {
        runner.withPropertyValues("chat.redis.enabled=true", "chat.redis.environment=local:*",
                "chat.redis.host=localhost", "chat.redis.port=6379", "chat.redis.connect-timeout=1s",
                "chat.redis.command-timeout=1s").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void environmentNamespacesAreExactAndRejectWildcardInjection() {
        var local = new ChatRedisChannels(properties("local"));
        var staging = new ChatRedisChannels(properties("staging"));
        assertThat(local.roomChannel(12)).isEqualTo("local:chat:room:12");
        assertThat(local.roomPattern()).isEqualTo("local:chat:room:*");
        assertThat(local.controlChannel()).isEqualTo("local:chat:control:v1");
        assertThat(staging.roomChannel(12)).isNotEqualTo(local.roomChannel(12));
        for (String invalid : new String[]{"", "local*", "local:other", "local?", "local[ab]", "LOCAL", " local"}) {
            assertThrows(IllegalArgumentException.class, () -> properties(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> local.roomChannel(0));
    }

    @Test
    void credentialsAreRedactedAndTimeoutsAreValidated() {
        assertThat(properties("local").toString()).doesNotContain("test-password", "localhost");
        assertThrows(IllegalArgumentException.class, () -> new ChatRedisProperties("local", "localhost", 6379,
                "", "", false, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ChatRedisProperties("local", "localhost", 70000,
                "", "", false, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ChatRedisProperties("local", "localhost", 6379,
                "acl-user", "", false, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private ChatRedisProperties properties(String environment) {
        return new ChatRedisProperties(environment, "localhost", 6379, "", "test-password", false,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void enabledBindingKeepsTlsAndBoundedQueuesWithoutYamlDefaults() {
        var source = new MapConfigurationPropertySource(Map.of("chat.redis.environment", "local",
                "chat.redis.password", "test-password"));
        var properties = new Binder(source).bind("chat.redis", Bindable.of(ChatRedisProperties.class)).get();
        assertThat(properties.ssl()).isTrue();
        assertThat(properties.username()).isEqualTo("default");
        assertThat(properties.requestQueueSize()).isEqualTo(1024);
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.sslCaCertificate()).isEmpty();
    }

    @Test
    void enabledConnectionRequiresPasswordAndBoundedQueue() {
        assertThrows(IllegalArgumentException.class, () -> new ChatRedisProperties("local", "localhost", 6379,
                "", "", false, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        for (int invalid : new int[]{0, -1, 65537}) {
            assertThrows(IllegalArgumentException.class, () -> new ChatRedisProperties("local", "localhost", 6379,
                    "chat", "test-password", false, Duration.ofSeconds(1), Duration.ofSeconds(1), invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ChatRedisProperties("local", "localhost", 6379,
                "chat", "test-password", true, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024,
                "/missing-test-ca.pem"));
    }

    @Test
    void stateKeysIsolateEpochsAndRejectSlotOrNamespaceInjection() {
        var channels = new ChatRedisChannels(properties("staging"));
        assertThat(channels.roomStateKey(12, 2, "presence"))
                .isEqualTo("staging:chat:state:{room:12}:e:2:presence");
        assertThat(channels.roomStateKey(12, 3, "presence"))
                .isNotEqualTo(channels.roomStateKey(12, 2, "presence"));
        for (String invalid : new String[]{"", "a:b", "*", "x{room:99}", "../x"}) {
            assertThrows(IllegalArgumentException.class, () -> channels.roomStateKey(12, 2, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> channels.roomStateKey(0, 2, "presence"));
        assertThrows(IllegalArgumentException.class, () -> channels.roomStateKey(12, 0, "presence"));
    }
}
