package com.muse.meomuneum.chat.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.redis")
public record ChatRedisProperties(String environment, String host, int port, String username, String password,
        boolean ssl, Duration connectTimeout, Duration commandTimeout) {

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
        if (username != null && !username.isBlank() && (password == null || password.isBlank())) {
            throw new IllegalArgumentException("chat.redis username requires a password");
        }
    }

    @Override
    public String toString() {
        return "ChatRedisProperties[connection details redacted]";
    }
}
