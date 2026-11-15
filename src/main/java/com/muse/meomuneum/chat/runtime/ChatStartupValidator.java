package com.muse.meomuneum.chat.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.muse.meomuneum.chat.message.policy.ChatMessageFingerprint;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;
import com.muse.meomuneum.global.security.AuthRevocationProtocol;

/** Checks resolved settings before bean creation can open connections or change participation state. */
public final class ChatStartupValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final List<String> REQUIRED_FLAGS = List.of("chat.runtime.enabled", "chat.core-auth.enabled",
            "chat.redis.enabled", "chat.core-auth.redis-enabled", "chat.distributed-presence.enabled",
            "chat.message-relay.enabled", "chat.control-events.enabled", "chat.lifecycle.enabled");
    private static final Set<String> NON_PRODUCTION = Set.of("chat-isolated", "test", "loadtest", "local-mock",
            "music-record-local", "music-record-signup-local");
    private static final Set<String> NON_PRODUCTION_NAMESPACES = Set.of("local", "test", "loadtest", "dev", "staging");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        validate(context.getEnvironment());
    }

    public void validate(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            String runtime = value(environment, "chat.runtime.enabled", "false");
            require("true".equalsIgnoreCase(runtime) || "false".equalsIgnoreCase(runtime), "chat.runtime.enabled");
            if (Boolean.parseBoolean(runtime) && !environment.acceptsProfiles(Profiles.of("chat-isolated"))) {
                throw new IllegalStateException("Chat runtime requires isolated verification outside prod");
            }
            return;
        }
        require(NON_PRODUCTION.stream().noneMatch(profile -> environment.acceptsProfiles(Profiles.of(profile))),
                "spring.profiles.active");
        for (String flag : REQUIRED_FLAGS) {
            require("true".equalsIgnoreCase(value(environment, flag, "false")), flag);
        }
        validateAuthentication(environment);
        validateRedis(environment);
        validateDatabase(environment);
        validatePolicy(environment);
    }

    private void validateAuthentication(Environment environment) {
        URI core = uri(required(environment, "chat.core-auth.base-url"), "chat.core-auth.base-url");
        require(secureOrigin(core) && (core.getRawPath().isEmpty() || "/".equals(core.getRawPath()))
                && !loopback(core.getHost()), "chat.core-auth.base-url");
        String service = required(environment, "chat.core-auth.service-token");
        require(service.length() >= 32 && service.length() <= 256
                && service.chars().noneMatch(Character::isWhitespace), "chat.core-auth.service-token");
        require(required(environment, "auth.jwt.secret").getBytes(StandardCharsets.UTF_8).length >= 32,
                "auth.jwt.secret");
        require(required(environment, "location.token.secret").getBytes(StandardCharsets.UTF_8).length >= 32,
                "location.token.secret");
        required(environment, "auth.jwt.issuer");
        required(environment, "auth.jwt.audience");
        for (String producer : required(environment, "chat.core-auth.producer-ids").split(",", -1)) {
            require(AuthRevocationProtocol.validProducer(producer.trim()), "chat.core-auth.producer-ids");
        }
        for (String origin : required(environment, "auth.cors.allowed-origins").split(",", -1)) {
            URI parsed = uri(origin.trim(), "auth.cors.allowed-origins");
            require(secureOrigin(parsed) && parsed.getRawPath().isEmpty() && !loopback(parsed.getHost())
                    && !parsed.getHost().contains("*"), "auth.cors.allowed-origins");
        }
        validateSecrets(environment);
    }

    private void validateSecrets(Environment environment) {
        var secrets = new ArrayList<byte[]>();
        for (String property : List.of("auth.jwt.secret", "location.token.secret", "chat.core-auth.service-token",
                "chat.redis.password")) {
            addSecret(secrets, required(environment, property).getBytes(StandardCharsets.UTF_8), property);
        }
        String specification = required(environment, "chat.message-relay.fingerprint-keys");
        try {
            new ChatMessageFingerprint(specification);
        } catch (RuntimeException invalid) {
            throw invalid("chat.message-relay.fingerprint-keys");
        }
        for (String entry : specification.split(",")) {
            byte[] key = Base64.getDecoder().decode(entry.trim().split(":", 2)[1]);
            addSecret(secrets, key, "chat.message-relay.fingerprint-keys");
        }
        String metrics = value(environment, "chat.lifecycle.metrics-token", "");
        if (!metrics.isEmpty()) {
            require(metrics.matches("[A-Za-z0-9._~-]{32,256}"), "chat.lifecycle.metrics-token");
            addSecret(secrets, metrics.getBytes(StandardCharsets.UTF_8), "chat.lifecycle.metrics-token");
        }
    }

    private void addSecret(List<byte[]> secrets, byte[] secret, String property) {
        require(secrets.stream().noneMatch(previous -> Arrays.equals(previous, secret)), property);
        secrets.add(secret);
    }

    private void validateRedis(Environment environment) {
        require("true".equalsIgnoreCase(value(environment, "chat.redis.ssl", "true")), "chat.redis.ssl");
        String username = required(environment, "chat.redis.username");
        require(!"default".equalsIgnoreCase(username.trim()), "chat.redis.username");
        String namespace = required(environment, "chat.redis.environment");
        require(!NON_PRODUCTION_NAMESPACES.contains(namespace), "chat.redis.environment");
        try {
            new ChatRedisProperties(namespace, required(environment, "chat.redis.host"),
                    integer(environment, "chat.redis.port", "6379"), username,
                    required(environment, "chat.redis.password"), true,
                    duration(environment, "chat.redis.connect-timeout", "1s"),
                    duration(environment, "chat.redis.command-timeout", "1s"),
                    integer(environment, "chat.redis.request-queue-size", "1024"),
                    value(environment, "chat.redis.ssl-ca-certificate", ""));
        } catch (RuntimeException invalid) {
            throw invalid("chat.redis");
        }
    }

    private void validateDatabase(Environment environment) {
        String url = required(environment, "spring.datasource.url");
        require(url.startsWith("jdbc:mysql://") && url.length() > "jdbc:mysql://".length(), "spring.datasource.url");
        required(environment, "spring.datasource.username");
        required(environment, "spring.datasource.password");
        require(integer(environment, "spring.datasource.hikari.maximum-pool-size", "10") > 0,
                "spring.datasource.hikari.maximum-pool-size");
        require("validate".equals(value(environment, "spring.jpa.hibernate.ddl-auto", "")),
                "spring.jpa.hibernate.ddl-auto");
        require("false".equalsIgnoreCase(value(environment, "spring.flyway.enabled", "false")),
                "spring.flyway.enabled");
        require("never".equals(value(environment, "spring.sql.init.mode", "never")), "spring.sql.init.mode");
    }

    private void validatePolicy(Environment environment) {
        require(Duration.ofSeconds(1).equals(duration(environment, "chat.message-policy.minimum-interval", "1s")),
                "chat.message-policy.minimum-interval");
        require("true".equalsIgnoreCase(value(environment, "chat.message-policy.detection-enabled", "false")),
                "chat.message-policy.detection-enabled");
        require("graceful".equals(value(environment, "server.shutdown", "")), "server.shutdown");
        Duration drain = duration(environment, "chat.lifecycle.drain-timeout", "20s");
        Duration phase = duration(environment, "spring.lifecycle.timeout-per-shutdown-phase", "30s");
        require(drain.compareTo(Duration.ofSeconds(1)) >= 0 && drain.compareTo(Duration.ofSeconds(25)) <= 0
                && drain.compareTo(phase) < 0, "chat.lifecycle.drain-timeout");
    }

    private boolean secureOrigin(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
    }

    private boolean loopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.equals("localhost.")
                || normalized.startsWith("127.") || normalized.equals("[::1]") || normalized.equals("[0:0:0:0:0:0:0:1]")
                || normalized.equals("0.0.0.0");
    }

    private URI uri(String value, String property) {
        try {
            return URI.create(value);
        } catch (RuntimeException invalid) {
            throw invalid(property);
        }
    }

    private int integer(Environment environment, String property, String fallback) {
        try {
            return Integer.parseInt(value(environment, property, fallback));
        } catch (RuntimeException invalid) {
            throw invalid(property);
        }
    }

    private Duration duration(Environment environment, String property, String fallback) {
        try {
            return DurationStyle.detectAndParse(value(environment, property, fallback));
        } catch (RuntimeException invalid) {
            throw invalid(property);
        }
    }

    private String required(Environment environment, String property) {
        String setting = value(environment, property, "");
        require(!setting.isBlank(), property);
        return setting;
    }

    private String value(Environment environment, String property, String fallback) {
        try {
            return environment.getProperty(property, fallback);
        } catch (RuntimeException invalid) {
            // Placeholder and conversion exceptions may contain the original configuration value.
            throw invalid(property);
        }
    }

    private void require(boolean allowed, String property) {
        if (!allowed) {
            throw invalid(property);
        }
    }

    private IllegalStateException invalid(String property) {
        return new IllegalStateException("Invalid Chat startup setting: " + property);
    }
}
