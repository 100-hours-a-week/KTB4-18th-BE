package com.muse.meomuneum.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.hazelcast.config.Config;
import com.hazelcast.config.YamlConfigBuilder;

class LocalDevelopmentConfigurationTest {

    private static final String HMAC_KEY = "test-only-hmac-key-with-at-least-32-bytes";
    private static final String REPLAY_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir
    private Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "dev,test", "test,dev"})
    void importsPropertiesAndBindsLocalAuthenticationDefaults(String profiles) throws Exception {
        Path properties = localProperties();
        StandardEnvironment environment = environment(profiles, properties, Map.of());
        String selected = profiles.endsWith("dev") ? "dev" : "test";

        assertThat(environment.getProperty("probe.imported")).isEqualTo("true");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://localhost:3306/" + selected + "_fixture");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo(selected + "-fixture-password");
        assertThat(environment.getProperty("auth.jwt.secret"))
                .isEqualTo((profiles.contains("test") ? "test" : "dev")
                        + "-only-imported-jwt-secret-at-least-32-bytes");
        assertThat(environment.getProperty("location.token.secret"))
                .isEqualTo("test-only-imported-location-secret-at-least-32-bytes");
        assertThat(environment.getProperty("auth.state.hazelcast.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("auth.state.hazelcast.local-auto-start", Boolean.class)).isTrue();
        assertThat(environment.getProperty("auth.state.hazelcast.members")).isEqualTo("127.0.0.1:15702");
        assertThat(environment.getProperty("auth.state.hazelcast.cluster-name")).isEqualTo("meomuneum-auth-local");
        assertThat(environment.getProperty("auth.state.backend-replica-count")).isEqualTo("1");
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("auth.cookie.secure", Boolean.class)).isFalse();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
        assertThat(environment.getProperty("auth.cors.local-all-ports-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("auth.cors.allowed-origins"))
                .contains("http://localhost:5174", "http://127.0.0.1:5174");
        AuthStateProperties state = state(environment);
        assertThat(state.hmacKey()).isEqualTo(HMAC_KEY);
        assertThat(state.replayEncryptionKey()).isEqualTo(REPLAY_KEY);
        assertThat(state.expectedHazelcastMembers()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void operatingSystemEnvironmentOverridesTheImportedPropertiesAndLocalDefaults(String profile) throws Exception {
        StandardEnvironment environment = environment(profile, localProperties(), Map.ofEntries(
                Map.entry(profile.equals("dev") ? "DEV_DB_PASSWORD" : "TEST_DB_PASSWORD", "override-password"),
                Map.entry(profile.equals("dev") ? "AUTH_JWT_SECRET" : "TEST_AUTH_JWT_SECRET",
                        "local-only-overriding-jwt-secret-at-least-32-bytes"),
                Map.entry("AUTH_COOKIE_SECURE", "true"),
                Map.entry("AUTH_STATE_HAZELCAST_LOCAL_AUTO_START", "false"),
                Map.entry("AUTH_STATE_HAZELCAST_ENABLED", "false"),
                Map.entry("AUTH_STATE_HAZELCAST_MEMBERS", "127.0.0.1:5802"),
                Map.entry("AUTH_STATE_HAZELCAST_CLUSTER_NAME", "explicit-local-cluster"),
                Map.entry("AUTH_STATE_HAZELCAST_EXPECTED_MEMBER_COUNT", "2"),
                Map.entry("AUTH_BACKEND_REPLICA_COUNT", "2"),
                Map.entry("AUTH_CORS_LOCAL_ALL_PORTS_ENABLED", "false"),
                Map.entry("SERVER_ADDRESS", "localhost"),
                Map.entry("SERVER_PORT", "18082")));

        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("override-password");
        assertThat(environment.getProperty("auth.jwt.secret"))
                .isEqualTo("local-only-overriding-jwt-secret-at-least-32-bytes");
        assertThat(environment.getProperty("auth.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("auth.state.hazelcast.members")).isEqualTo("127.0.0.1:5802");
        assertThat(environment.getProperty("auth.state.hazelcast.local-auto-start", Boolean.class)).isFalse();
        assertThat(environment.getProperty("auth.cors.local-all-ports-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(18082);
        assertThat(environment.getProperty("server.address")).isEqualTo("localhost");
        assertThat(environment.getProperty("auth.state.hazelcast.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("auth.state.hazelcast.cluster-name")).isEqualTo("explicit-local-cluster");
        assertThat(environment.getProperty("auth.state.backend-replica-count")).isEqualTo("2");
        assertThat(state(environment).expectedHazelcastMembers()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "other", "prod", "dev,prod", "prod,dev", "test,prod", "prod,test",
            "dev,test,prod", "dev,prod,test", "prod,dev,test", "test,dev,prod", "test,prod,dev", "prod,test,dev"})
    void excludesLocalImportAndDefaultsOutsideLocalProfilesWithoutProduction(String profiles) throws Exception {
        Path malformed = temporaryDirectory.resolve("malformed.properties");
        Files.writeString(malformed, "probe.imported=\\uZZZZ\n");
        StandardEnvironment environment = environment(profiles, malformed, Map.of());

        assertThat(environment.getProperty("probe.imported")).isNull();
        assertThat(environment.getProperty("spring.config.import")).isNull();
        assertThat(environment.getProperty("auth.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("auth.state.hazelcast.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("auth.state.hazelcast.local-auto-start")).isNull();
        assertThat(environment.getProperty("auth.state.hazelcast.cluster-name")).isEqualTo("meomuneum-auth");
        assertThat(environment.getProperty("auth.cors.local-all-ports-enabled")).isNull();
        assertThat(environment.getProperty("server.address")).isNull();
        if (profiles.contains("prod")) {
            assertThatThrownBy(() -> environment.getProperty("spring.datasource.password"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(profiles.lastIndexOf("dev") > profiles.lastIndexOf("prod")
                            ? "DEV_DB_PASSWORD"
                            : "PROD_DB_PASSWORD");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void acceptsExternalVariablesWithoutALocalFileAndRetainsRequiredKeyValidation(String profile) {
        StandardEnvironment environment = environment(profile, temporaryDirectory.resolve("missing.properties"),
                Map.of("AUTH_STATE_HMAC_KEY", HMAC_KEY, "AUTH_STATE_REPLAY_ENCRYPTION_KEY", REPLAY_KEY));
        HazelcastAuthStateConfiguration configuration = new HazelcastAuthStateConfiguration();

        assertThatThrownBy(() -> configuration.authHazelcastClient("unused", "", Duration.ofSeconds(1),
                "1", state(environment)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_STATE_HAZELCAST_MEMBERS");
        for (String invalidHmac : new String[]{"", "x".repeat(31)}) {
            StandardEnvironment invalid = environment(profile, temporaryDirectory.resolve("missing.properties"),
                    Map.of("AUTH_STATE_HMAC_KEY", invalidHmac,
                            "AUTH_STATE_REPLAY_ENCRYPTION_KEY", REPLAY_KEY));
            assertThatThrownBy(() -> configuration.authHazelcastClient("unused", "", Duration.ofSeconds(1),
                    "1", state(invalid)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_STATE_HMAC_KEY");
        }
        for (String invalidReplay : new String[]{"", "invalid-base64!",
                Base64.getEncoder().encodeToString(new byte[31])}) {
            StandardEnvironment invalid = environment(profile, temporaryDirectory.resolve("missing.properties"),
                    Map.of("AUTH_STATE_HMAC_KEY", HMAC_KEY,
                            "AUTH_STATE_REPLAY_ENCRYPTION_KEY", invalidReplay));
            assertThatThrownBy(() -> configuration.authHazelcastClient("unused", "", Duration.ofSeconds(1),
                    "1", state(invalid)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_STATE_REPLAY_ENCRYPTION_KEY");
        }
        for (String replicas : new String[]{"", "2"}) {
            assertThatThrownBy(() -> configuration.authHazelcastClient("unused", "", Duration.ofSeconds(1),
                    replicas, state(environment)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_BACKEND_REPLICA_COUNT");
        }
    }

    @Test
    void localMemberIsIsolatedAndHasEnoughQueueCapacity() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("config/hazelcast-auth-state-local.yaml"))) {
            Config config = new YamlConfigBuilder(input).build();
            assertThat(config.getClusterName()).isEqualTo("meomuneum-auth-local");
            assertThat(config.getNetworkConfig().getPort()).isEqualTo(15702);
            assertThat(config.getNetworkConfig().isPortAutoIncrement()).isFalse();
            assertThat(config.getNetworkConfig().getInterfaces().isEnabled()).isTrue();
            assertThat(config.getNetworkConfig().getInterfaces().getInterfaces()).containsExactly("127.0.0.1");
            assertThat(config.getNetworkConfig().getJoin().getAutoDetectionConfig().isEnabled()).isFalse();
            assertThat(config.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()).isFalse();
            assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().isEnabled()).isFalse();
            assertThat(config.getQueueConfig("login-queue-order-v2").getMaxSize()).isEqualTo(2150);
            assertThat(config.getJetConfig().isEnabled()).isFalse();
            assertThat(config.getProperty("hazelcast.socket.server.bind.any")).isEqualTo("false");
        }
    }

    @Test
    void devKeepsRequiredSecretsWithoutIntroducingFixtureFallbacks() {
        StandardEnvironment environment = environment("dev", temporaryDirectory.resolve("missing.properties"),
                Map.of());
        for (String property : new String[]{"spring.datasource.password", "auth.jwt.secret", "location.token.secret"}) {
            assertThatThrownBy(() -> environment.getProperty(property)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(environment.getProperty("auth.state.hmac-key")).isEmpty();
        assertThat(environment.getProperty("auth.state.replay-encryption-key")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "dev,prod", "prod,dev", "dev,test,prod", "dev,prod,test",
            "prod,dev,test", "test,dev,prod", "test,prod,dev", "prod,test,dev"})
    void retainsTheOriginalDevDocumentSettingsAndMixedProfilePriority(String profiles) {
        StandardEnvironment environment = environment(profiles, temporaryDirectory.resolve("missing.properties"),
                Map.of("DEV_DB_PASSWORD", "dev-password", "PROD_DB_PASSWORD", "prod-password"));
        boolean devDatabaseWins = profiles.lastIndexOf("dev") > profiles.lastIndexOf("prod");
        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo(devDatabaseWins ? "dev-password" : "prod-password");
        assertThat(environment.getProperty("recommendation.allow-guests", Boolean.class)).isTrue();
        assertThat(environment.getProperty("recommendation.provider")).isEqualTo("ai");
        assertThat(environment.getProperty("auth.cors.allowed-origins"))
                .isEqualTo("http://localhost:5174,http://127.0.0.1:5174");
        assertThat(environment.getProperty("speech.transcription.provider"))
                .isEqualTo(devDatabaseWins ? "stub" : "ai");
    }

    private Path localProperties() throws Exception {
        Path path = temporaryDirectory.resolve("local.properties");
        Files.writeString(path, """
                probe.imported=true
                DEV_DB_URL=jdbc:mysql://localhost:3306/dev_fixture
                DEV_DB_PASSWORD=dev-fixture-password
                AUTH_JWT_SECRET=dev-only-imported-jwt-secret-at-least-32-bytes
                TEST_DB_URL=jdbc:mysql://localhost:3306/test_fixture
                TEST_DB_PASSWORD=test-fixture-password
                TEST_AUTH_JWT_SECRET=test-only-imported-jwt-secret-at-least-32-bytes
                LOCATION_TOKEN_SECRET=test-only-imported-location-secret-at-least-32-bytes
                AUTH_STATE_HMAC_KEY=%s
                AUTH_STATE_REPLAY_ENCRYPTION_KEY=%s
                """.formatted(HMAC_KEY, REPLAY_KEY));
        return path;
    }

    private StandardEnvironment environment(String profiles, Path localFile, Map<String, Object> overrides) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> input = new HashMap<>();
        input.put("spring.config.location", "classpath:/");
        input.put("spring.profiles.active", profiles);
        input.put("LOCAL_ENV_PROPERTIES", localFile.toString());
        environment.getPropertySources().addFirst(new MapPropertySource("configuration-test", input));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("simulated-os", overrides));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    private AuthStateProperties state(StandardEnvironment environment) {
        return Binder.get(environment).bind("auth.state", AuthStateProperties.class).get();
    }
}
