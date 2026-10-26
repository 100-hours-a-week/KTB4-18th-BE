package com.muse.appsplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.sun.net.httpserver.HttpServer;

/** Executes packaged apps, never a test component scan or test security chain. */
@Testcontainers
class AppSeparationProcessTest {
    private static final String SECRET = "split-synthetic-test-secret-at-least-32-bytes";
    private static final String ORIGIN = "http://localhost:5174";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("app_split").withUsername("split").withPassword("split-test-only-password");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Node> nodes = new ArrayList<>();
    private final Path evidence = Path.of("build/app-separation", "run-" + Instant.now().toEpochMilli());

    @Test
    void packagedAppsEnforceOwnershipAndPreserveSingleChatRestAndStomp() throws Exception {
        Files.createDirectories(evidence);
        verifyArtifacts();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:src/main/resources/db/migration").load().migrate();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        long region = jdbc.queryForObject("SELECT id FROM regions WHERE code='41135'", Long.class);
        jdbc.update("DELETE FROM chat_rooms WHERE region_id=?", region);
        jdbc.update("""
                INSERT INTO users (id,email,password_hash,nickname,role,created_at)
                VALUES (98701,'split@example.test','unused-test-hash','splituser','USER',UTC_TIMESTAMP(6))
                """);
        String sid = UUID.randomUUID().toString();
        String token = accessToken(sid);
        HttpServer geography = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        geography.createContext("/", exchange -> {
            byte[] body = "{\"documents\":[{\"region_type\":\"B\",\"code\":\"4113510100\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        geography.start();
        try {
            Node core = start("core", false, geography.getAddress().getPort(), token);
            assertEquals(0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE region_id=?", Integer.class, region));
            for (String path : List.of("/ws", "/ws/socket", "/api/v1/chat-rooms/1/members",
                    "/api/v1/regions/" + region + "/chat-room")) {
                assertEquals(404, request(core, "GET", path, token, null).statusCode());
            }
            assertEquals(200, request(core, "GET", "/api/v1/users/me", token, null).statusCode());
            var resolution = request(core, "POST", "/api/v1/locations/resolve", token,
                    "{\"latitude\":37.3595704,\"longitude\":127.105399,\"accuracy_meters\":10}");
            assertEquals(200, resolution.statusCode(), resolution.body());
            String locationToken = mapper.readTree(resolution.body()).path("data").path("location_resolution_token")
                    .asText();
            assertFalse(locationToken.isBlank());

            Node dormant = start("chat", false, 0, null);
            assertEquals(503, request(dormant, "GET", "/actuator/health/readiness", null, null).statusCode());
            assertEquals(503, request(dormant, "GET", "/ws", token, null).statusCode());
            assertEquals(0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE region_id=?", Integer.class, region));
            stop(dormant);

            Node chat = start("chat", true, 0, null);
            assertEquals(200, request(chat, "GET", "/actuator/health/readiness", null, null).statusCode());
            for (String path : List.of("/api/v1/auth/login", "/api/v1/users/me", "/api/v1/locations/resolve",
                    "/api/v1/recommendations", "/internal/v1/auth/sessions/validate")) {
                assertEquals(404, request(chat, "GET", path, token, null).statusCode());
            }
            long room = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE region_id=?", Long.class, region);
            assertEquals(401,
                    request(chat, "GET", "/api/v1/regions/" + region + "/chat-room", null, null).statusCode());
            assertEquals(200,
                    request(chat, "GET", "/api/v1/regions/" + region + "/chat-room", token, null).statusCode());
            var join = request(chat, "POST", "/api/v1/chat-rooms/" + room + "/members", token,
                    mapper.writeValueAsString(Map.of("location_resolution_token", locationToken)));
            assertEquals(201, join.statusCode(), join.body());
            assertFalse(join.headers().firstValue("Set-Cookie").isPresent());
            JsonNode membership = mapper.readTree(join.body()).path("data");
            long member = membership.path("membership_id").asLong();
            Probe probe = connect(chat, token, room, member, null);
            try {
                String body = mapper.writeValueAsString(Map.of("client_message_id", UUID.randomUUID().toString(),
                        "content", "분리 앱 검증 메시지"));
                probe.send("SEND\ndestination:/app/chat-rooms/" + room + "/messages\ncontent-type:application/json\n\n"
                        + body);
                probe.await("CHAT_ACK");
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Integer.class));
                String view = probe.ready.path("view_session_id").asText();
                assertPage(chat, token, room, member, view, "history");
                probe.socket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").get(5, TimeUnit.SECONDS);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? "
                        + "AND ends_at IS NULL", Integer.class, view) > 0 && System.nanoTime() < end) {
                    Thread.sleep(50);
                }
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? "
                        + "AND ends_at IS NULL", Integer.class, view));
                probe = connect(chat, token, room, member, view);
                assertEquals(view, probe.ready.path("view_session_id").asText());
                assertPage(chat, token, room, member, view, "history");
                assertPage(chat, token, room, member, view, "recovery");
            } finally {
                if (probe.socket != null) {
                    probe.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                }
            }
            stop(core);
            assertEquals(200, request(chat, "GET", "/actuator/health/readiness", null, null).statusCode());
            Files.writeString(evidence.resolve("result.json"), mapper.writeValueAsString(Map.of(
                    "corePid", core.process().pid(), "chatPid", chat.process().pid(),
                    "ownership", true, "coreLocationTokenAcceptedByChat", true, "stompAck", true,
                    "historyRecoveryAfterReconnect", true,
                    "scope", "isolated single Chat; no Core session validation or distributed state")));
        } finally {
            geography.stop(0);
        }
    }

    @Test
    void prematureActivationIsRejectedBeforeDatabaseOrChatStartup() throws Exception {
        Files.createDirectories(evidence);
        for (String profile : List.of("prod", "dev")) {
            Path log = evidence.resolve("rejected-" + profile + ".log");
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-jar", System.getProperty("app.split.chat-jar"), "--chat.runtime.enabled=true",
                    "--spring.profiles.active=" + profile);
            builder.environment().clear();
            Process child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
                assertTrue(child.exitValue() != 0);
                String output = Files.readString(log);
                assertTrue(output.contains("Chat runtime requires isolated verification"));
                assertFalse(output.contains("HikariPool"));
                assertFalse(output.contains("SimpleBrokerMessageHandler"));
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void builtImagesStartAsNonRootWithOnlyTheirRuntimeDependencies() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("app.split.images"),
                "enable -PappSplitImages=true after building images");
        Files.createDirectories(evidence);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:src/main/resources/db/migration").load().migrate();
        String url = "jdbc:mysql://host.docker.internal:" + MYSQL.getMappedPort(3306) + "/app_split";
        for (String role : List.of("core", "chat")) {
            try (var container = new GenericContainer<>("meomuneum-" + role + ":app-separation-check")
                    .withExtraHost("host.docker.internal", "host-gateway").withExposedPorts(8080)
                    .withEnv(role.toUpperCase() + "_DB_URL", url)
                    .withEnv(role.toUpperCase() + "_DB_USERNAME", MYSQL.getUsername())
                    .withEnv(role.toUpperCase() + "_DB_PASSWORD", MYSQL.getPassword())
                    .withEnv("AUTH_JWT_SECRET", SECRET).withEnv("LOCATION_TOKEN_SECRET", SECRET)
                    .withEnv("AUTH_STATE_HAZELCAST_ENABLED", "false")
                    .waitingFor(Wait.forLogMessage(".*Started " + (role.equals("core") ? "Core" : "Chat")
                            + "Application.*", 1))) {
                if (role.equals("core")) {
                    container.withEnv("RECOMMENDATION_AI_BASE_URL", "http://127.0.0.1:1")
                            .withEnv("SPEECH_TRANSCRIPTION_AI_BASE_URL", "http://127.0.0.1:1")
                            .withEnv("LOCATION_REVERSE_GEOCODING_BASE_URL", "http://127.0.0.1:1");
                }
                container.start();
                assertEquals("10001", container.execInContainer("id", "-u").getStdout().trim());
                if (role.equals("core")) {
                    assertEquals(0, container.execInContainer("ffmpeg", "-version").getExitCode());
                } else {
                    assertEquals(0, container.execInContainer("sh", "-c", "test ! -e /usr/bin/ffmpeg").getExitCode());
                }
                String base = "http://" + container.getHost() + ":" + container.getMappedPort(8080);
                var builder = HttpRequest.newBuilder(URI.create(base + "/actuator/health/liveness"));
                assertEquals(200, http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                if (role.equals("chat")) {
                    var response = http.send(HttpRequest.newBuilder(URI.create(base + "/ws")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, response.statusCode());
                }
                Files.writeString(evidence.resolve(role + "-image.log"), container.getLogs());
            }
        }
        Files.writeString(evidence.resolve("images-result.json"), "{\"core\":true,\"chat\":true,\"nonRoot\":true}");
    }

    private void verifyArtifacts() throws Exception {
        try (var core = new JarFile(System.getProperty("app.split.core-jar"));
                var chat = new JarFile(System.getProperty("app.split.chat-jar"))) {
            assertEquals("com.muse.apps.core.CoreApplication",
                    core.getManifest().getMainAttributes().getValue("Start-Class"));
            assertEquals("com.muse.apps.chat.ChatApplication",
                    chat.getManifest().getMainAttributes().getValue("Start-Class"));
            assertFalse(core.stream().anyMatch(e -> e.getName().contains("/chat/connection/")
                    || e.getName().contains("/chat/view/")
                    || e.getName().contains("spring-websocket")));
            assertFalse(chat.stream().anyMatch(e -> e.getName().contains("/auth/application/")
                    || e.getName().contains("/user/account/") || e.getName().contains("/recommendation/")
                    || e.getName().contains("hazelcast") || e.getName().contains("db/migration/")
                    || e.getName().contains("foundationprobe") || e.getName().contains("flyway")));
        }
    }

    private Probe connect(Node chat, String token, long room, long member, String view) throws Exception {
        Probe probe = new Probe();
        probe.socket = http.newWebSocketBuilder().header("Origin", ORIGIN).subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://127.0.0.1:" + chat.port() + "/ws"), probe).get(10, TimeUnit.SECONDS);
        try {
            probe.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token
                    + "\nroom_id:" + room + "\nmembership_id:" + member
                    + (view == null ? "" : "\nview_session_id:" + view + "\nlast_message_id:0") + "\n\n");
            assertTrue(probe.await("CONNECTED").startsWith("CONNECTED"));
            probe.send("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + room + "\nreceipt:room\n\n");
            probe.await("RECEIPT");
            probe.send("SUBSCRIBE\nid:events\ndestination:/user/queue/chat-events\nreceipt:events\n\n");
            String frame = probe.await("CHAT_READY");
            probe.ready = mapper.readTree(frame.substring(frame.indexOf("\n\n") + 2).replace("\u0000", ""))
                    .path("data");
            return probe;
        } catch (Exception | AssertionError failure) {
            probe.socket.abort();
            throw failure;
        }
    }

    private void assertPage(Node chat, String token, long room, long member, String view, String mode)
            throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + chat.port()
                + "/api/v1/chat-rooms/" + room + "/messages?membership_id=" + member + "&mode=" + mode + "&size=10"))
                .header("Authorization", "Bearer " + token).header("X-Chat-View-Session", view).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode items = mapper.readTree(response.body()).path("data").path("items");
        assertEquals(1, items.size());
        assertEquals("분리 앱 검증 메시지", items.get(0).path("content").asText());
    }

    private Node start(String role, boolean enabled, int geographyPort, String token) throws Exception {
        int port;
        try (var free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-jar", System.getProperty("app.split." + role + "-jar"), "--server.address=127.0.0.1",
                "--server.port=" + port, "--chat.message-cleanup.enabled=false"));
        if (enabled) {
            command.add("--spring.profiles.active=chat-isolated");
        }
        if (role.equals("core")) {
            command.add("--management.endpoint.health.group.readiness.include=readinessState,db");
            command.add("--spring.profiles.active=prod");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(Map.ofEntries(Map.entry(role.toUpperCase() + "_DB_URL", MYSQL.getJdbcUrl()),
                Map.entry(role.toUpperCase() + "_DB_USERNAME", MYSQL.getUsername()),
                Map.entry(role.toUpperCase() + "_DB_PASSWORD", MYSQL.getPassword()),
                Map.entry("AUTH_JWT_SECRET", SECRET), Map.entry("LOCATION_TOKEN_SECRET", SECRET),
                Map.entry("AUTH_CORS_ALLOWED_ORIGINS", ORIGIN), Map.entry("AUTH_STATE_HAZELCAST_ENABLED", "false"),
                Map.entry("CHAT_RUNTIME_ENABLED", Boolean.toString(enabled))));
        if (role.equals("core")) {
            builder.environment().putAll(Map.of("RECOMMENDATION_AI_BASE_URL", "http://127.0.0.1:1",
                    "SPEECH_TRANSCRIPTION_AI_BASE_URL", "http://127.0.0.1:1",
                    "LOCATION_REVERSE_GEOCODING_BASE_URL", "http://127.0.0.1:" + geographyPort,
                    "LOCATION_KAKAO_REST_API_KEY", "synthetic-test-key"));
        }
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve(role + "-" + enabled + ".log").toFile());
        Node node = new Node(builder.start(), port);
        nodes.add(node);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) {
            assertTrue(node.process().isAlive(), "app exited; inspect build/app-separation logs");
            try {
                if (request(node, "GET", "/actuator/health/liveness", token, null).statusCode() == 200) {
                    return node;
                }
            } catch (Exception notReady) {
                // Wait only for this child process, never a pre-existing application.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("app startup deadline exceeded");
    }

    private HttpResponse<String> request(Node node, String method, String path, String token, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.port() + path))
                .timeout(java.time.Duration.ofSeconds(5)).header("Origin", ORIGIN)
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String accessToken(String sid) {
        User user = new User() {
        };
        ReflectionTestUtils.setField(user, "id", 98701L);
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        return new JwtTokenProvider(new JwtProperties("project-api", "project-api", SECRET, 3600, 1209600))
                .createAccessToken(user, sid);
    }

    private void stop(Node node) throws Exception {
        node.process().destroy();
        if (!node.process().waitFor(15, TimeUnit.SECONDS)) {
            node.process().destroyForcibly();
            assertTrue(node.process().waitFor(5, TimeUnit.SECONDS));
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        for (Node node : nodes) {
            if (node.process().isAlive()) {
                stop(node);
            }
        }
    }

    private record Node(Process process, int port) {
    }

    private final class Probe implements WebSocket.Listener {
        private WebSocket socket;
        private JsonNode ready;
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private final List<String> pending = new ArrayList<>();

        private void send(String frame) {
            socket.sendText(frame + "\u0000", true).join();
        }

        private String await(String expected) throws Exception {
            for (int index = 0; index < pending.size(); index++) {
                if (pending.get(index).contains(expected)) {
                    return pending.remove(index);
                }
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end) {
                String frame = frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if (frame.contains(expected)) {
                        return frame;
                    }
                    pending.add(frame);
                }
            }
            throw new AssertionError("missing STOMP frame " + expected);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                frames.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return onText(webSocket, StandardCharsets.UTF_8.decode(data), last);
        }
    }
}
