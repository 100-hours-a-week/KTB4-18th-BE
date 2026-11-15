package com.muse.appsplit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Exercises the real Chat entrypoint; valid settings proceed only to an intentionally unreachable test DB. */
class ChatStartupProcessTest {
    @Test
    void invalidProductionFailsBeforeDataSourceOrBrokerInitialization() throws Exception {
        for (List<String> options : List.of(List.of("--chat.runtime.enabled=false"),
                List.of("--chat.runtime.enabled=true", "--chat.redis.ssl=false"),
                List.of("--spring.profiles.active=prod,chat-isolated"))) {
            String output = launch(options);
            assertTrue(output.contains("Invalid Chat startup setting:"));
            assertFalse(output.contains("HikariPool"));
            assertFalse(output.contains("SimpleBrokerMessageHandler"));
        }
    }

    @Test
    void validProductionPassesStartupValidationAndFailsOnlyAtUnavailableDatabase() throws Exception {
        String output = launch(List.of());
        assertFalse(output.contains("Invalid Chat startup setting:"));
        assertFalse(output.contains("Chat runtime requires isolated verification"));
        assertTrue(output.contains("HikariPool"), "valid prod must reach normal datasource initialization");
        assertTrue(output.contains("Connection refused") || output.contains("Communications link failure"));
    }

    private String launch(List<String> options) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-jar", System.getProperty("chat.startup.jar"), "--spring.profiles.active=prod",
                "--spring.main.banner-mode=off", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.hikari.initialization-fail-timeout=1"));
        command.addAll(options);
        var builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(Map.ofEntries(
                Map.entry("AUTH_JWT_SECRET", "synthetic-process-jwt-key-at-least-32-bytes"),
                Map.entry("LOCATION_TOKEN_SECRET", "synthetic-process-location-key-at-least-32-bytes"),
                Map.entry("CORE_AUTH_SERVICE_TOKEN", "synthetic-process-service-key-at-least-32-bytes"),
                Map.entry("CORE_INTERNAL_BASE_URL", "https://core.example.test"),
                Map.entry("CORE_AUTH_PRODUCER_IDS", "core:11111111-1111-4111-8111-111111111111"),
                Map.entry("AUTH_CORS_ALLOWED_ORIGINS", "https://web.example.test"),
                Map.entry("CHAT_DB_URL", "jdbc:mysql://127.0.0.1:1/chat?connectTimeout=500&socketTimeout=500"),
                Map.entry("CHAT_DB_USERNAME", "chat"), Map.entry("CHAT_DB_PASSWORD", "synthetic-db-password"),
                Map.entry("CHAT_REDIS_HOST", "redis.example.test"), Map.entry("CHAT_REDIS_USERNAME", "chat-app"),
                Map.entry("CHAT_REDIS_PASSWORD", "synthetic-process-redis-password"),
                Map.entry("CHAT_REDIS_ENVIRONMENT", "prod"),
                Map.entry("CHAT_MESSAGE_FINGERPRINT_KEYS", "k1:" + Base64.getEncoder().encodeToString(
                        "synthetic-process-fingerprint-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))),
                Map.entry("CHAT_RUNTIME_ENABLED", "true"), Map.entry("CHAT_CORE_AUTH_ENABLED", "true"),
                Map.entry("CHAT_REDIS_ENABLED", "true"), Map.entry("CHAT_AUTH_REDIS_ENABLED", "true"),
                Map.entry("CHAT_DISTRIBUTED_PRESENCE_ENABLED", "true"), Map.entry("CHAT_MESSAGE_RELAY_ENABLED", "true"),
                Map.entry("CHAT_CONTROL_EVENTS_ENABLED", "true"), Map.entry("CHAT_LIFECYCLE_ENABLED", "true")));
        Path directory = Path.of("build/chat-startup-verification");
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, "process-", ".log");
        Process child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "inspect " + log);
            assertTrue(child.exitValue() != 0, "fixture must not start a service");
            return Files.readString(log);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
