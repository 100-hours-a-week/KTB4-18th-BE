package com.muse.meomuneum.chat.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.lettuce.core.RedisCommandExecutionException;

/** Real local TLS/ACL fixtures; no deployment credentials or endpoints are used. */
@Testcontainers
class ChatRedisSecurityIntegrationTest {
    private static final String PASSWORD = "foundation-synthetic-test-password";
    private static final String IMAGE = System.getProperty("chat.foundation.redis-image", "redis:8.10.2");

    @TempDir
    Path directory;

    @Test
    void tlsRequiresTrustedCertificateAndMatchingHostname() throws Exception {
        Path store = directory.resolve("fixture.p12");
        Process generator = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                "-genkeypair", "-alias", "redis", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-ext", "bc=ca:true",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD, "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(generator.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, generator.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, PASSWORD.toCharArray());
        }
        String certificate = pem("CERTIFICATE", keys.getCertificate("redis").getEncoded());
        String key = pem("PRIVATE KEY", keys.getKey("redis", PASSWORD.toCharArray()).getEncoded());
        Path ca = directory.resolve("ca.pem");
        Files.writeString(ca, certificate);
        try (var redis = new GenericContainer<>(IMAGE).withExposedPorts(6379)
                .withCopyToContainer(Transferable.of(certificate), "/fixture/server.crt")
                .withCopyToContainer(Transferable.of(key), "/fixture/server.key")
                .withCommand("redis-server", "--port", "0", "--tls-port", "6379", "--tls-auth-clients", "no",
                        "--tls-cert-file", "/fixture/server.crt", "--tls-key-file", "/fixture/server.key",
                        "--tls-ca-cert-file", "/fixture/server.crt", "--requirepass", PASSWORD,
                        "--appendonly", "no", "--save", "")) {
            redis.start();
            int port = redis.getMappedPort(6379);
            try (var connection = new ChatRedisConnection(tls("localhost", port, ca.toString()))) {
                assertEquals("PONG", connection.commands().sync().ping());
                assertTrue(connection.isConnected());
            }
            var untrusted = assertThrows(IllegalStateException.class,
                    () -> new ChatRedisConnection(tls("localhost", port, "")));
            assertFalse(untrusted.toString().contains(PASSWORD));
            assertThrows(IllegalStateException.class,
                    () -> new ChatRedisConnection(tls("127.0.0.1", port, ca.toString())));
        }
    }

    @Test
    void noevictionRejectsOversizedWriteWithoutEvictingExistingProjection() {
        try (var redis = new GenericContainer<>(IMAGE).withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", PASSWORD, "--maxmemory", "8mb",
                        "--maxmemory-policy", "noeviction", "--appendonly", "no", "--save", "")) {
            redis.start();
            var properties = new ChatRedisProperties("local", redis.getHost(), redis.getMappedPort(6379),
                    "default", PASSWORD, false, Duration.ofSeconds(2), Duration.ofSeconds(5));
            try (var connection = new ChatRedisConnection(properties)) {
                var commands = connection.commands().sync();
                commands.set("local:chat:existing", "retained");
                RedisCommandExecutionException failure;
                try {
                    commands.set("local:chat:large", "x".repeat(16 * 1024 * 1024));
                    failure = assertThrows(RedisCommandExecutionException.class,
                            () -> commands.set("local:chat:rejected", "new"));
                } catch (RedisCommandExecutionException rejectedBeforeAllocation) {
                    // Redis may account for the incoming request before allocating the value.
                    failure = rejectedBeforeAllocation;
                }
                assertTrue(failure.getMessage().contains("OOM"));
                assertEquals("retained", commands.get("local:chat:existing"));
                assertEquals("PONG", commands.ping());
                commands.del("local:chat:large");
                assertEquals("OK", commands.set("local:chat:recovered", "ready"));
            }
        }
    }

    private ChatRedisProperties tls(String host, int port, String ca) {
        return new ChatRedisProperties("local", host, port, "default", PASSWORD, true,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 1024, ca);
    }

    private String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
