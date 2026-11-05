package com.muse.meomuneum.chat.redis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("chat.redis")
public record ChatRedisProperties(String environment, @DefaultValue("localhost") String host,
        @DefaultValue("6379") int port, @DefaultValue("default") String username, String password,
        @DefaultValue("true") boolean ssl, @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("1s") Duration commandTimeout, @DefaultValue("1024") int requestQueueSize,
        @DefaultValue("") String sslCaCertificate) {

    public ChatRedisProperties(String environment, String host, int port, String username, String password,
            boolean ssl, Duration connectTimeout, Duration commandTimeout) {
        this(environment, host, port, username, password, ssl, connectTimeout, commandTimeout, 1024, "");
    }

    public ChatRedisProperties(String environment, String host, int port, String username, String password,
            boolean ssl, Duration connectTimeout, Duration commandTimeout, int requestQueueSize) {
        this(environment, host, port, username, password, ssl, connectTimeout, commandTimeout, requestQueueSize, "");
    }

    @ConstructorBinding
    public ChatRedisProperties {
        if (environment == null || !environment.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("chat.redis.environment must be a lowercase environment identifier");
        }
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("chat.redis requires a host and a valid port");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("chat.redis timeouts must be positive");
        }
        username = username == null || username.isBlank() ? "default" : username;
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("chat.redis requires an authentication password when enabled");
        }
        if (requestQueueSize < 1 || requestQueueSize > 65536) {
            throw new IllegalArgumentException("chat.redis.request-queue-size must be between 1 and 65536");
        }
        sslCaCertificate = sslCaCertificate == null ? "" : sslCaCertificate;
        if (!sslCaCertificate.isEmpty()
                && (!ssl || !Files.isRegularFile(Path.of(sslCaCertificate))
                        || !Files.isReadable(Path.of(sslCaCertificate)))) {
            throw new IllegalArgumentException("chat.redis.ssl-ca-certificate requires TLS and a readable PEM file");
        }
    }

    @Override
    public String toString() {
        return "ChatRedisProperties[connection details redacted]";
    }
}
