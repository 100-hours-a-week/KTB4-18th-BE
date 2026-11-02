package com.muse.meomuneum.chat.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

class ChatStartupValidatorTest {
    private static final String JWT = "synthetic-jwt-secret-with-at-least-32-bytes";
    private static final String LOCATION = "synthetic-location-secret-with-at-least-32-bytes";
    private static final String SERVICE = "synthetic-service-secret-with-at-least-32-bytes";
    private static final String REDIS = "synthetic-redis-password-only";
    private static final String FINGERPRINT = "synthetic-fingerprint-key-with-at-least-32-bytes";
    private static final List<String> FLAGS = List.of("chat.runtime.enabled", "chat.core-auth.enabled",
            "chat.redis.enabled", "chat.core-auth.redis-enabled", "chat.distributed-presence.enabled",
            "chat.message-relay.enabled", "chat.control-events.enabled", "chat.lifecycle.enabled");
    private final ChatStartupValidator validator = new ChatStartupValidator();

    @Test
    void productionSettingsPassWithoutOpeningAnyConnections() {
        assertDoesNotThrow(() -> validator.validate(production()));
    }

    @Test
    void missingFalseAndMalformedFeatureFlagsFailIncludingDormantProduction() {
        for (String property : FLAGS) {
            for (String value : List.of("", "false", "yes", "1", "true ")) {
                MockEnvironment environment = production().withProperty(property, value);
                assertEquals("Invalid Chat startup setting: " + property,
                        assertThrows(IllegalStateException.class, () -> validator.validate(environment)).getMessage());
            }
            MockEnvironment missing = production();
            missing.getPropertySources().replace("mockProperties", without(missing, property));
            assertThrows(IllegalStateException.class, () -> validator.validate(missing));
        }
    }

    @Test
    void productionCannotUseTestProfilesEvenWhenRuntimeIsDisabled() {
        for (String profile : List.of("chat-isolated", "test", "loadtest", "local-mock", "music-record-local",
                "music-record-signup-local")) {
            MockEnvironment environment = production().withProperty("chat.runtime.enabled", "false");
            environment.setActiveProfiles("prod", profile);
            assertEquals("Invalid Chat startup setting: spring.profiles.active",
                    assertThrows(IllegalStateException.class, () -> validator.validate(environment)).getMessage());
        }
    }

    @Test
    void isolatedPartialAndDormantApplicationsKeepTheirExistingBehavior() {
        var isolated = new MockEnvironment().withProperty("chat.runtime.enabled", "true");
        isolated.setActiveProfiles("chat-isolated");
        assertDoesNotThrow(() -> validator.validate(isolated));
        assertDoesNotThrow(() -> validator.validate(new MockEnvironment()));
        for (String profile : List.of("dev", "default")) {
            var environment = new MockEnvironment().withProperty("chat.runtime.enabled", "true");
            environment.setActiveProfiles(profile);
            assertThrows(IllegalStateException.class, () -> validator.validate(environment));
        }
    }

    @Test
    void defaultProfilesCannotBypassProductionValidation() {
        MockEnvironment environment = production();
        environment.setActiveProfiles();
        environment.setDefaultProfiles("prod");
        assertDoesNotThrow(() -> validator.validate(environment));
        environment.setDefaultProfiles("prod", "chat-isolated");
        assertThrows(IllegalStateException.class, () -> validator.validate(environment));
    }

    @Test
    void insecureOrMalformedCoreUrlsAreRejectedWithoutEchoingValues() {
        for (String url : List.of("http://core.example.test", "https://localhost", "https://127.0.0.1",
                "https://[::1]", "https://core.example.test/path", "https://core.example.test?token=" + SERVICE,
                "https://user:" + SERVICE + "@core.example.test", "https://core.example.test#fragment",
                "https://core.example.test:65536", "https://bad host/")) {
            reject("chat.core-auth.base-url", url);
        }
        assertDoesNotThrow(() -> validator.validate(production()
                .withProperty("chat.core-auth.base-url", "https://core.example.test/")));
    }

    @Test
    void originsRequireExactHttpsOriginsAndNeverLoopbackOrWildcard() {
        for (String origin : List.of("", "*", "http://web.example.test", "https://*.example.test",
                "https://localhost", "https://127.0.0.2", "https://[::1]", "https://web.example.test/",
                "https://web.example.test/path", "https://web.example.test?x=1", "https://web.example.test,",
                "https://user:secret@web.example.test", "https://web.example.test:0")) {
            reject("auth.cors.allowed-origins", origin);
        }
        assertDoesNotThrow(() -> validator.validate(production().withProperty("auth.cors.allowed-origins",
                "https://web.example.test, https://other.example.test:8443")));
    }

    @Test
    void requiredCredentialsAndTokenMetadataAreValidated() {
        for (String property : List.of("auth.jwt.secret", "location.token.secret", "auth.jwt.issuer",
                "auth.jwt.audience", "chat.core-auth.service-token", "chat.redis.password",
                "spring.datasource.username", "spring.datasource.password")) {
            reject(property, "");
        }
        reject("auth.jwt.secret", "short");
        reject("location.token.secret", "short");
        reject("chat.core-auth.service-token", "short");
        reject("chat.core-auth.service-token", "x".repeat(257));
        reject("chat.core-auth.service-token", SERVICE + " ");
        reject("chat.core-auth.producer-ids", "core:not-a-uuid");
        reject("chat.core-auth.producer-ids", "core:11111111-1111-4111-8111-111111111111,");
    }

    @Test
    void secretsCannotBeReusedIncludingDecodedFingerprintMaterial() {
        reject("location.token.secret", JWT);
        reject("chat.core-auth.service-token", JWT);
        reject("chat.redis.password", SERVICE);
        reject("chat.message-relay.fingerprint-keys", key("k1", JWT));
        reject("chat.message-relay.fingerprint-keys", key("k1", FINGERPRINT) + "," + key("k2", FINGERPRINT));
        reject("chat.lifecycle.metrics-token", JWT);
    }

    @Test
    void fingerprintAndOptionalMetricsKeysKeepExistingFormatRules() {
        for (String value : List.of("", "k1:not-base64", key("k1", "short"),
                key("k1", FINGERPRINT) + "," + key("k1", JWT))) {
            reject("chat.message-relay.fingerprint-keys", value);
        }
        assertDoesNotThrow(() -> validator.validate(production().withProperty("chat.lifecycle.metrics-token", "")));
        assertDoesNotThrow(() -> validator.validate(production().withProperty("chat.lifecycle.metrics-token",
                "synthetic-distinct-metrics-token-of-32-characters")));
        reject("chat.lifecycle.metrics-token", "short");
    }

    @Test
    void redisRequiresTlsDedicatedAccountAndProductionNamespace() {
        reject("chat.redis.ssl", "false");
        reject("chat.redis.ssl", "invalid");
        reject("chat.redis.username", "default");
        reject("chat.redis.username", "");
        for (String namespace : List.of("local", "test", "loadtest", "dev", "staging", "prod:*", "PROD", "")) {
            reject("chat.redis.environment", namespace);
        }
        reject("chat.redis.host", "");
        reject("chat.redis.port", "65536");
        reject("chat.redis.request-queue-size", "65537");
        reject("chat.redis.connect-timeout", "0s");
        reject("chat.redis.command-timeout", "bad-duration");
        reject("chat.redis.ssl-ca-certificate", "/nonexistent-chat-test-ca.pem");
    }

    @Test
    void databaseMustValidateSchemaWithoutRunningInitialization() {
        reject("spring.datasource.url", "jdbc:h2:mem:chat");
        reject("spring.datasource.hikari.maximum-pool-size", "0");
        reject("spring.jpa.hibernate.ddl-auto", "update");
        reject("spring.flyway.enabled", "true");
        reject("spring.sql.init.mode", "always");
    }

    @Test
    void productionKeepsMessagePolicyAndBoundedGracefulDrain() {
        reject("chat.message-policy.minimum-interval", "500ms");
        reject("chat.message-policy.detection-enabled", "false");
        reject("server.shutdown", "immediate");
        for (String duration : List.of("0s", "26s", "30s", "bad-duration")) {
            reject("chat.lifecycle.drain-timeout", duration);
        }
        reject("spring.lifecycle.timeout-per-shutdown-phase", "20s");
    }

    @Test
    void unresolvedSecretsNeverReachTheExceptionChain() {
        var environment = production().withProperty("chat.core-auth.service-token", "${missing:" + SERVICE + " }");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator.validate(environment));
        assertFalse(failure.getMessage().contains(SERVICE));
        assertNull(failure.getCause());
    }

    @Test
    void initializerRejectsInvalidProductionBeforeCreatingBeans() {
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(production().withProperty("chat.runtime.enabled", "false"));
            context.registerBean("mustNotBeCreated", Object.class, () -> {
                throw new AssertionError("must not create beans before validation");
            });
            assertThrows(IllegalStateException.class, () -> validator.initialize(context));
            assertFalse(context.isActive());
        }
    }

    @Test
    void realChatYamlResolvesInjectedSettingsWithoutRequiringNetwork() throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addLast(new MapPropertySource("deployment", Map.ofEntries(
                Map.entry("AUTH_JWT_SECRET", JWT), Map.entry("LOCATION_TOKEN_SECRET", LOCATION),
                Map.entry("CORE_AUTH_SERVICE_TOKEN", SERVICE),
                Map.entry("CORE_INTERNAL_BASE_URL", "https://core.example.test"),
                Map.entry("CORE_AUTH_PRODUCER_IDS", "core:11111111-1111-4111-8111-111111111111"),
                Map.entry("AUTH_CORS_ALLOWED_ORIGINS", "https://web.example.test"),
                Map.entry("CHAT_DB_URL", "jdbc:mysql://db.example.test/chat"),
                Map.entry("CHAT_DB_USERNAME", "chat"), Map.entry("CHAT_DB_PASSWORD", "synthetic-db-password"),
                Map.entry("CHAT_REDIS_USERNAME", "chat-app"), Map.entry("CHAT_REDIS_PASSWORD", REDIS),
                Map.entry("CHAT_REDIS_HOST", "redis.example.test"), Map.entry("CHAT_REDIS_ENVIRONMENT", "prod"),
                Map.entry("CHAT_MESSAGE_FINGERPRINT_KEYS", key("k1", FINGERPRINT)),
                Map.entry("CHAT_RUNTIME_ENABLED", "true"), Map.entry("CHAT_CORE_AUTH_ENABLED", "true"),
                Map.entry("CHAT_REDIS_ENABLED", "true"), Map.entry("CHAT_AUTH_REDIS_ENABLED", "true"),
                Map.entry("CHAT_DISTRIBUTED_PRESENCE_ENABLED", "true"), Map.entry("CHAT_MESSAGE_RELAY_ENABLED", "true"),
                Map.entry("CHAT_CONTROL_EVENTS_ENABLED", "true"), Map.entry("CHAT_LIFECYCLE_ENABLED", "true"))));
        for (var source : new YamlPropertySourceLoader().load("chat-yaml",
                new FileSystemResource("src/chat/resources/application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        assertDoesNotThrow(() -> validator.validate(environment));
    }

    private void reject(String property, String value) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator.validate(production().withProperty(property, value)), property);
        assertTrue(failure.getMessage().startsWith("Invalid Chat startup setting:"));
        for (String secret : List.of(JWT, LOCATION, SERVICE, REDIS, FINGERPRINT)) {
            assertFalse(failure.getMessage().contains(secret));
        }
        assertNull(failure.getCause());
    }

    private MapPropertySource without(MockEnvironment environment, String property) {
        var properties = new java.util.HashMap<String, Object>();
        var source = (MapPropertySource) environment.getPropertySources().get("mockProperties");
        properties.putAll(source.getSource());
        properties.remove(property);
        return new MapPropertySource("mockProperties", properties);
    }

    private MockEnvironment production() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        FLAGS.forEach(property -> environment.withProperty(property, "true"));
        return environment.withProperty("auth.jwt.secret", JWT).withProperty("auth.jwt.issuer", "project-api")
                .withProperty("auth.jwt.audience", "project-api").withProperty("location.token.secret", LOCATION)
                .withProperty("chat.core-auth.base-url", "https://core.example.test")
                .withProperty("chat.core-auth.service-token", SERVICE)
                .withProperty("chat.core-auth.producer-ids", "core:11111111-1111-4111-8111-111111111111")
                .withProperty("auth.cors.allowed-origins", "https://web.example.test")
                .withProperty("chat.redis.environment", "prod").withProperty("chat.redis.host", "redis.example.test")
                .withProperty("chat.redis.username", "chat-app").withProperty("chat.redis.password", REDIS)
                .withProperty("chat.message-relay.fingerprint-keys", key("k1", FINGERPRINT))
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example.test/chat")
                .withProperty("spring.datasource.username", "chat")
                .withProperty("spring.datasource.password", "synthetic-db-password")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate").withProperty("server.shutdown", "graceful")
                .withProperty("chat.message-policy.detection-enabled", "true");
    }

    private String key(String id, String material) {
        return id + ":" + Base64.getEncoder().encodeToString(material.getBytes(StandardCharsets.UTF_8));
    }
}
