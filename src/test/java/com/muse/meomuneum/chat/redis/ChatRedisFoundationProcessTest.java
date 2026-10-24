package com.muse.meomuneum.chat.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.PortBinding;

@Testcontainers
class ChatRedisFoundationProcessTest {
    private static final String PASSWORD = "foundation-test-only-password";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_foundation").withUsername("foundation").withPassword(PASSWORD);
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.6")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD, "--maxmemory-policy",
                    "noeviction")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withPortBindings(PortBinding.parse("127.0.0.1:" + redisPort() + ":6379")));
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Node> nodes = new ArrayList<>();
    private final Path evidence = Path.of("build/chat-redis-foundation", "run-" + Instant.now().toEpochMilli());

    @Test
    void twoActualApplicationProcessesShareDatabaseAndRedisAndRecoverSubscriptions() throws Exception {
        Files.createDirectories(evidence);
        Node first = start("first");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC", MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("CREATE TABLE foundation_probe (marker VARCHAR(100) PRIMARY KEY)");
        Node second = start("second");
        assertNotEquals(first.process().pid(), second.process().pid());
        assertEquals(status(first).path("database"), status(second).path("database"));
        String marker = "shared-" + UUID.randomUUID();
        assertEquals(200, request(first, "/marker", marker).statusCode());
        assertTrue(request(second, "/markers", null).body().contains(marker));

        String forward = publish(first, "room");
        await(() -> received(first, forward) == 1 && received(second, forward) == 1);
        String backward = publish(second, "control");
        await(() -> received(first, backward) == 1 && received(second, backward) == 1);

        var properties = new ChatRedisProperties("foundation", REDIS.getHost(), REDIS.getMappedPort(6379),
                "", PASSWORD, false, Duration.ofSeconds(1), Duration.ofSeconds(1));
        try (var publisher = new ChatRedisConnection(properties)) {
            publisher.commands().sync().publish("other:chat:room:1", "wrong-environment");
            publisher.commands().sync().publish("foundation-extra:chat:room:1", "wrong-prefix");
            publisher.commands().sync().publish("foundation:chat:control:v2", "wrong-version");
        }
        String barrier = publish(first, "control");
        await(() -> received(second, barrier) == 1);
        assertFalse(status(first).toString().contains("wrong-"));
        assertFalse(status(second).toString().contains("wrong-"));
        assertEquals(200, health(first, "readiness"));
        assertEquals(200, health(second, "readiness"));

        var invalid = new ChatRedisProperties("foundation", REDIS.getHost(), REDIS.getMappedPort(6379),
                "", "invalid-test-password", false, Duration.ofSeconds(1), Duration.ofSeconds(1));
        var failure = assertThrows(IllegalStateException.class, () -> new ChatRedisConnection(invalid));
        assertFalse(failure.toString().contains("invalid-test-password"));

        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
        await(() -> health(first, "readiness") == 503 && health(second, "readiness") == 503);
        await(() -> disconnected(first) && disconnected(second));
        assertEquals(200, health(first, "liveness"));
        assertEquals(200, health(second, "liveness"));
        REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        await(() -> health(first, "readiness") == 200 && health(second, "readiness") == 200);
        // PUBLISH's recipient count confirms that both subscriptions have actually returned, not merely PING.
        String recovered = "recovered-" + UUID.randomUUID();
        await(() -> publishToBoth(first, recovered));
        await(() -> received(first, recovered) >= 1 && received(second, recovered) >= 1);
        String finalMessage = publish(second, "control");
        await(() -> received(first, finalMessage) == 1 && received(second, finalMessage) == 1);

        stop(first);
        assertFalse(first.process().isAlive());
        assertEquals(200, health(second, "readiness"));
        String surviving = publish(second, "room");
        await(() -> received(second, surviving) == 1);
        Files.writeString(evidence.resolve("result.json"), mapper.writeValueAsString(Map.of(
                "firstPid", first.process().pid(), "secondPid", second.process().pid(),
                "database", "chat_foundation", "bidirectionalPubSub", true, "namespaceIsolation", true,
                "redisRestartAndResubscribe", true, "livenessDuringRedisOutage", true,
                "sigtermAndSurvivingProcess", true, "productClusterReady", false)));
    }

    private Node start(String name) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Duser.timezone=UTC", "-Dspring.devtools.restart.enabled=false", "-cp",
                System.getProperty("chat.foundation.classpath"), "com.muse.foundationprobe.ChatRedisProbeApplication",
                "--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=" + port,
                "--auth.state.hazelcast.enabled=false",
                "--chat.message-cleanup.enabled=false", "--recommendation.ai.base-url=http://127.0.0.1:1",
                "--speech.transcription.ai.base-url=http://127.0.0.1:1",
                "--location.reverse-geocoding.base-url=http://127.0.0.1:1",
                "--management.endpoint.health.group.readiness.include=readinessState,db,chatRedis",
                "--server.shutdown=graceful", "--spring.lifecycle.timeout-per-shutdown-phase=5s");
        builder.environment().putAll(Map.of("TEST_DB_URL", MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC",
                "TEST_DB_USERNAME", MYSQL.getUsername(), "TEST_DB_PASSWORD", MYSQL.getPassword(),
                "CHAT_REDIS_ENABLED", "true", "CHAT_REDIS_ENVIRONMENT", "foundation",
                "CHAT_REDIS_HOST", REDIS.getHost(), "CHAT_REDIS_PORT", REDIS.getMappedPort(6379).toString(),
                "CHAT_REDIS_PASSWORD", PASSWORD, "CHAT_REDIS_SSL", "false"));
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve(name + ".log").toFile());
        Node node = new Node(builder.start(), port);
        nodes.add(node);
        await(() -> {
            assertTrue(node.process().isAlive(), "child exited; inspect build/chat-redis-foundation logs");
            try {
                return request(node, "/status", null).statusCode() == 200;
            } catch (Exception notStarted) {
                return false;
            }
        });
        return node;
    }

    private JsonNode status(Node node) throws Exception {
        return mapper.readTree(request(node, "/status", null).body());
    }

    private boolean disconnected(Node node) {
        try {
            return status(node).path("connected").asDouble() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private long received(Node node, String message) {
        try {
            long count = 0;
            for (JsonNode value : status(node).path("received")) {
                if (message.equals(value.path("body").asText())) {
                    count++;
                }
            }
            return count;
        } catch (Exception unavailable) {
            return 0;
        }
    }

    private String publish(Node node, String kind) throws Exception {
        String message = mapper.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(),
                "version", 1, "type", "FOUNDATION_PROBE"));
        var response = request(node, "/publish", mapper.writeValueAsString(Map.of("kind", kind, "body", message)));
        assertEquals(200, response.statusCode());
        return message;
    }

    private boolean publishToBoth(Node node, String message) {
        try {
            var response = request(node, "/publish",
                    mapper.writeValueAsString(Map.of("kind", "room", "body", message)));
            return response.statusCode() == 200 && mapper.readTree(response.body()).path("subscribers").asLong() == 2;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private HttpResponse<String> request(Node node, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.port() + "/foundation-probe" + path))
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json");
        return http.send(body == null
                ? request.GET().build()
                : request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int health(Node node, String group) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.port()
                    + "/actuator/health/" + group)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception unavailable) {
            return 0;
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("foundation condition timed out; inspect build/chat-redis-foundation logs");
    }

    private void stop(Node node) throws Exception {
        node.process().destroy();
        assertTrue(node.process().waitFor(20, TimeUnit.SECONDS), "child did not stop after SIGTERM");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (Node node : nodes) {
            if (node.process().isAlive()) {
                node.process().destroy();
                if (!node.process().waitFor(20, TimeUnit.SECONDS)) {
                    node.process().destroyForcibly();
                    node.process().waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private record Node(Process process, int port) {
    }

    private static int redisPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Could not allocate an isolated Redis port");
        }
    }
}
