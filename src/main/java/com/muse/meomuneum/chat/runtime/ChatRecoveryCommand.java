package com.muse.meomuneum.chat.runtime;

import java.time.Duration;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

/** Run with the Chat JAR PropertiesLauncher, while routing is closed and old Chat Tasks are stopped. */
public final class ChatRecoveryCommand {
    private ChatRecoveryCommand() {
    }

    public static void main(String[] args) {
        if (args.length != 0 || !"chat-stopped".equals(System.getenv("CHAT_RECOVERY_CONFIRM"))) {
            throw new IllegalArgumentException("Stop Chat routing/Tasks and set CHAT_RECOVERY_CONFIRM=chat-stopped");
        }
        String stage = "configuration";
        try {
            var source = new DriverManagerDataSource(required("CHAT_DB_URL"), required("CHAT_DB_USERNAME"),
                    required("CHAT_DB_PASSWORD"));
            var connection = new java.util.Properties();
            connection.setProperty("connectTimeout", "1000");
            connection.setProperty("socketTimeout", "2000");
            source.setConnectionProperties(connection);
            var properties = new ChatRedisProperties(required("CHAT_REDIS_ENVIRONMENT"), required("CHAT_REDIS_HOST"),
                    Integer.parseInt(System.getenv().getOrDefault("CHAT_REDIS_PORT", "6379")),
                    System.getenv().getOrDefault("CHAT_REDIS_USERNAME", "default"), required("CHAT_REDIS_PASSWORD"),
                    tlsEnabled(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 1024,
                    System.getenv().getOrDefault("CHAT_REDIS_SSL_CA_CERTIFICATE", ""));
            var recovery = new ChatRuntimeRecovery(new JdbcTemplate(source), new DataSourceTransactionManager(source),
                    properties.environment());
            stage = "redis-connection";
            try (var redis = new ChatRedisConnection(properties)) {
                var channels = new ChatRedisChannels(properties);
                try (var subscription = redis.openSubscription(channels, new RedisPubSubAdapter<>())) {
                    stage = "begin";
                    var lease = recovery.begin(ChatRuntimeRecovery.serverId(redis));
                    stage = "chunks";
                    while (recovery.chunk(lease)) {
                        // A killed command leaves RECOVERING. A successor takes over only after the DB lease expires.
                    }
                    stage = "finish";
                    recovery.finish(lease, redis, channels);
                    LoggerFactory.getLogger(ChatRecoveryCommand.class).info("event=chat_recovery_completed epoch={}",
                            lease.epoch());
                }
            }
        } catch (RuntimeException failure) {
            // Driver/Redis exceptions can contain credentials and endpoints; keep command diagnostics bounded.
            LoggerFactory.getLogger(ChatRecoveryCommand.class).error("event=chat_recovery_failed stage={} exception={}",
                    stage, failure.getClass().getSimpleName());
            throw new IllegalStateException("Chat recovery did not complete; keep routing closed and inspect DB mode");
        }
    }

    private static boolean tlsEnabled() {
        String value = System.getenv().getOrDefault("CHAT_REDIS_SSL", "true");
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Chat Redis TLS flag must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Chat recovery environment is incomplete");
        }
        return value;
    }
}
