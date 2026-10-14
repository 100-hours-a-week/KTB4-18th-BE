package com.muse.meomuneum.chat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatRecoveryProcessIntegrationTest {
    private static final String SECRET = "test-only-auth-token-secret-that-is-not-used-outside-tests";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_process_tests").withUsername("chat_process_test").withPassword("test-only");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Path evidenceDirectory = Path.of("build/chat-process-verification",
            "run-" + Instant.now().toEpochMilli());
    private final List<Probe> probes = new ArrayList<>();
    private final List<String> completed = new ArrayList<>();
    private JdbcTemplate jdbc;
    private HttpServer geography;
    private Process server;
    private int port;
    private long room;
    private long region;
    private int starts;

    @BeforeAll
    void startEnvironment() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC",
                MYSQL.getUsername(), MYSQL.getPassword()));
        geography = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        geography.createContext("/", exchange -> {
            byte[] body = "{\"documents\":[{\"region_type\":\"B\",\"code\":\"4113510100\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        geography.start();
        startServer();
        region = jdbc.queryForObject("SELECT id FROM regions WHERE code='41135'", Long.class);
        room = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE region_id=?", Long.class, region);
    }

    private void startServer() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Path directory = evidenceDirectory;
        Files.createDirectories(directory);
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Duser.timezone=UTC", "-Dspring.devtools.restart.enabled=false", "-cp",
                System.getProperty("chat.process.classpath"),
                "com.muse.meomuneum.MeomuneumBackendApplication", "--spring.profiles.active=test",
                "--server.address=127.0.0.1", "--server.port=" + port, "--chat.message-cleanup.enabled=false",
                "--recommendation.ai.base-url=http://127.0.0.1:1",
                "--speech.transcription.ai.base-url=http://127.0.0.1:1",
                "--location.reverse-geocoding.base-url=http://127.0.0.1:" + geography.getAddress().getPort());
        builder.environment().put("TEST_DB_URL", MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
        builder.environment().put("TEST_DB_USERNAME", MYSQL.getUsername());
        builder.environment().put("TEST_DB_PASSWORD", MYSQL.getPassword());
        builder.environment().put("AUTH_CORS_ALLOWED_ORIGINS",
                "http://localhost:5174,http://127.0.0.1:5174,http://127.0.0.1:5175");
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("server-" + ++starts + ".log").toFile());
        server = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            assertTrue(server.isAlive(), "isolated server exited; inspect build/chat-process-verification logs");
            try {
                if (request("GET", "/api/v1/terms", null, null, null).statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException unavailable) {
                // Wait for this child server; never contact a configured product server.
            }
            Thread.sleep(200);
        }
        throw new AssertionError("isolated server did not become ready");
    }

    private Account account() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO users (email,password_hash,nickname,role) VALUES (?, 'test-only', ?, 'USER')",
                "process-" + suffix + "@example.com", "검증" + suffix);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class,
                "process-" + suffix + "@example.com");
        Instant now = Instant.now();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("project-api").subject(Long.toString(id)).audience("project-api")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("typ", "access").claim("roles", List.of("USER")).build());
        signed.sign(new MACSigner(SECRET));
        String token = signed.serialize();
        var location = request("POST", "/api/v1/locations/resolve", token, null,
                "{\"latitude\":37.3595704,\"longitude\":127.105399,\"accuracy_meters\":10}");
        assertEquals(200, location.statusCode(), "synthetic location resolution failed");
        String locationToken = mapper.readTree(location.body()).path("data").path("location_resolution_token").asText();
        var join = request("POST", "/api/v1/chat-rooms/" + room + "/members", token, null,
                mapper.writeValueAsString(Map.of("location_resolution_token", locationToken)));
        assertEquals(201, join.statusCode());
        long member = mapper.readTree(join.body()).path("data").path("membership_id").asLong();
        return new Account(id, token, member);
    }

    private HttpResponse<String> request(String method, String path, String token, String view, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (view != null) {
            builder.header("X-Chat-View-Session", view);
        }
        builder.header("Content-Type", "application/json");
        builder.header("Origin", "http://localhost:5174");
        return http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Probe connect(Account account, String view, long last) throws Exception {
        Probe probe = new Probe();
        probes.add(probe);
        probe.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5174")
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), probe)
                .get(10, TimeUnit.SECONDS);
        probe.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + account.token()
                + "\nroom_id:" + room + "\nmembership_id:" + account.member()
                + (view == null ? "" : "\nview_session_id:" + view + "\nlast_message_id:" + last) + "\n\n");
        assertTrue(probe.control.poll(10, TimeUnit.SECONDS).startsWith("CONNECTED"));
        probe.send("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + room + "\nreceipt:room\n\n");
        assertTrue(probe.control.poll(10, TimeUnit.SECONDS).startsWith("RECEIPT"));
        probe.send("SUBSCRIBE\nid:events\ndestination:/user/queue/chat-events\nreceipt:events\n\n");
        assertTrue(probe.control.poll(10, TimeUnit.SECONDS).startsWith("RECEIPT"));
        probe.ready = probe.events.get("CHAT_READY").poll(10, TimeUnit.SECONDS);
        assertTrue(probe.ready != null, "actual subscription readiness is required");
        return probe;
    }

    private JsonNode send(Probe probe, String clientId, String text) throws Exception {
        probe.send("SEND\ndestination:/app/chat-rooms/" + room + "/messages\ncontent-type:application/json\n\n"
                + mapper.writeValueAsString(Map.of("client_message_id", clientId, "content", text)));
        JsonNode ack = probe.events.get("CHAT_ACK").poll(10, TimeUnit.SECONDS);
        assertTrue(ack != null, "stored message ACK required");
        Thread.sleep(1050);
        return ack.path("message");
    }

    private HttpResponse<String> query(Account owner, Probe probe, String cursor, int size) throws Exception {
        return request("GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id=" + owner.member()
                + "&mode=recovery&size=" + size + (cursor == null ? "" : "&cursor=" + cursor), owner.token(),
                probe.ready.path("view_session_id").asText(), null);
    }

    private JsonNode page(Account account, Probe probe, String cursor, int size) throws Exception {
        var response = query(account, probe, cursor, size);
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body()).path("data");
    }

    @Test
    @Order(1)
    void shortReconnectSnapshotPagingLiveAndLostAckRetryUseOneStoredMessage() throws Exception {
        Account owner = account();
        Probe first = connect(owner, null, 0);
        JsonNode seen = send(first, UUID.randomUUID().toString(), "검증 연결 중");
        String view = first.ready.path("view_session_id").asText();
        first.socket.abort();
        Thread.sleep(300);
        Probe writer = connect(owner, null, 0);
        String client = UUID.randomUUID().toString();
        JsonNode missing = send(writer, client, "검증 짧은 단절");
        Probe resumed = connect(owner, view, seen.path("message_id").asLong());
        String cursor = resumed.ready.path("recovery_cursor").asText();
        JsonNode live = send(writer, UUID.randomUUID().toString(), "검증 복구 중 live");
        JsonNode restored = page(owner, resumed, cursor, 1);
        assertEquals(missing.path("message_id").asLong(), restored.path("items").get(0).path("message_id").asLong());
        assertFalse(restored.path("has_next").asBoolean());
        assertTrue(resumed.events.get("CHAT_MESSAGE").stream()
                .anyMatch(item -> item.path("message_id").asLong() == live.path("message_id").asLong()));
        send(writer, client, "검증 짧은 단절");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE client_message_id=?",
                Integer.class, client));
        JsonNode history = page(owner, resumed, null, 1);
        assertTrue(history.path("has_next").asBoolean());
        JsonNode next = page(owner, resumed, history.path("next_cursor").asText(), 1);
        assertNotEquals(history.path("items").get(0).path("message_id"), next.path("items").get(0).path("message_id"));
        completed.add("short-reconnect-snapshot-live-pagination-idempotent-retry");
    }

    @Test
    @Order(2)
    void disconnectedTabStopsAtThirtySecondsDespiteAnotherDeviceStayingLive() throws Exception {
        Account owner = account();
        Probe tab = connect(owner, null, 0);
        JsonNode before = send(tab, UUID.randomUUID().toString(), "검증 장기 단절 전");
        Probe device = connect(owner, null, 0);
        String view = tab.ready.path("view_session_id").asText();
        tab.socket.abort();
        Thread.sleep(500);
        JsonNode grace = send(device, UUID.randomUUID().toString(), "검증 유예 중");
        Thread.sleep(31000);
        JsonNode absent = send(device, UUID.randomUUID().toString(), "검증 부재 중");
        Probe resumed = connect(owner, view, before.path("message_id").asLong());
        JsonNode recovered = page(owner, resumed, resumed.ready.path("recovery_cursor").asText(), 50);
        List<Long> ids = new ArrayList<>();
        recovered.path("items").forEach(item -> ids.add(item.path("message_id").asLong()));
        assertTrue(ids.contains(grace.path("message_id").asLong()));
        assertFalse(ids.contains(absent.path("message_id").asLong()));
        assertEquals("ABSENCE", recovered.path("gaps").get(0).path("reason").asText());
        Probe fresh = connect(owner, null, 0);
        assertTrue(page(owner, fresh, null, 50).path("items").isEmpty());
        assertEquals(400, query(owner, fresh, resumed.ready.path("recovery_cursor").asText(), 50).statusCode());
        Account other = account();
        assertEquals(403, query(other, resumed, null, 50).statusCode());
        assertEquals(400, query(owner, resumed, "tampered", 50).statusCode());
        assertEquals(401, request("GET", "/api/v1/chat-rooms/" + room
                + "/messages?membership_id=" + owner.member(), null, view, null).statusCode());
        completed.add("long-disconnect-multi-device-new-tab-foreign-owner-cursor");
    }

    @Test
    @Order(3)
    void concurrentDepartureBanAndRetentionCannotExposeFurtherMessages() throws Exception {
        Account owner = account();
        Probe first = connect(owner, null, 0);
        Probe device = connect(owner, null, 0);
        JsonNode stored = send(first, UUID.randomUUID().toString(), "검증 만료 대상");
        var expirationRace = concurrentQuery(owner, first);
        jdbc.update("UPDATE chat_room_messages SET created_at=UTC_TIMESTAMP(6)-INTERVAL 24 HOUR WHERE id=?",
                stored.path("message_id").asLong());
        // Keep the expired message inside the recorded interval; retention must reject it independently.
        jdbc.update("UPDATE chat_view_intervals SET started_at=UTC_TIMESTAMP(6)-INTERVAL 25 HOUR "
                + "WHERE view_session_id=?", first.ready.path("view_session_id").asText());
        assertEquals(200, expirationRace.get(10, TimeUnit.SECONDS));
        assertTrue(page(owner, first, null, 50).path("items").isEmpty());
        String lookup = "/api/v1/chat-rooms/" + room + "/messages/by-client-id/"
                + stored.path("client_message_id").asText() + "?membership_id=" + owner.member();
        assertEquals(404, request("GET", lookup, owner.token(), first.ready.path("view_session_id").asText(), null)
                .statusCode());
        var racing = CompletableFuture.supplyAsync(() -> {
            try {
                return query(owner, device, null, 50).statusCode();
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        assertEquals(204, request("DELETE", "/api/v1/chat-rooms/" + room + "/members/me?membership_id="
                + owner.member(), owner.token(), null, null).statusCode());
        assertTrue(List.of(200, 403).contains(racing.get(10, TimeUnit.SECONDS)));
        assertEquals(4100, device.closed.get(10, TimeUnit.SECONDS));
        assertEquals(403, query(owner, device, null, 50).statusCode());
        Account banned = account();
        Probe connected = connect(banned, null, 0);
        var banRace = concurrentQuery(banned, connected);
        jdbc.update("INSERT INTO chat_bans (user_id,banned_by_user_id,reason,created_at,expires_at) "
                + "VALUES (?,?,'PROFANITY',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)", banned.id(),
                banned.id());
        assertTrue(List.of(200, 403).contains(banRace.get(10, TimeUnit.SECONDS)));
        assertEquals(403, query(banned, connected, null, 50).statusCode());
        completed.add("concurrent-departure-account-wide-close-ban-retention-own-lookup");
    }

    private CompletableFuture<Integer> concurrentQuery(Account account, Probe probe) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return query(account, probe, null, 50).statusCode();
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
    }

    @Test
    @Order(4)
    void forcedJvmRestartRecoversOnlyCheckpointedRecordsAndRejectsMissingView() throws Exception {
        Account owner = account();
        Probe first = connect(owner, null, 0);
        JsonNode proven = send(first, UUID.randomUUID().toString(), "검증 확인 기록");
        page(owner, first, null, 50);
        String view = first.ready.path("view_session_id").asText();
        long oldPid = server.pid();
        server.destroyForcibly();
        assertTrue(server.waitFor(10, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
        jdbc.update("INSERT INTO chat_room_messages (room_id,user_id,client_message_id,content,created_at) "
                + "VALUES (?,?,?,'검증 확인 부재',UTC_TIMESTAMP(6))", room, owner.id(), UUID.randomUUID().toString());
        startServer();
        assertNotEquals(oldPid, server.pid());
        Probe resumed = connect(owner, view, 0);
        JsonNode restored = page(owner, resumed, resumed.ready.path("recovery_cursor").asText(), 50);
        assertTrue(restored.path("items").toString().contains(proven.path("client_message_id").asText()));
        assertFalse(restored.path("items").toString().contains("검증 확인 부재"));
        assertEquals("RESTART", jdbc.queryForObject("SELECT end_reason FROM chat_view_intervals "
                + "WHERE view_session_id=? ORDER BY id LIMIT 1", String.class, view));
        jdbc.update("DELETE FROM chat_view_sessions WHERE id=?", view);
        assertEquals(403, query(owner, resumed, null, 50).statusCode());
        resumed.socket.abort();
        Thread.sleep(200);
        Probe rejected = new Probe();
        probes.add(rejected);
        rejected.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5174")
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), rejected)
                .get(10, TimeUnit.SECONDS);
        rejected.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + owner.token()
                + "\nroom_id:" + room + "\nmembership_id:" + owner.member()
                + "\nview_session_id:" + view + "\n\n");
        assertEquals(4103, rejected.closed.get(10, TimeUnit.SECONDS));
        assertTrue(rejected.events.get("CHAT_READY").isEmpty());
        Probe fresh = connect(owner, null, 0);
        assertTrue(page(owner, fresh, null, 50).path("items").isEmpty());
        completed.add("forced-jvm-restart-checkpoint-boundary-missing-record-new-view");
    }

    @Test
    @Order(5)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_RECOVERY_FE_DIR", matches = ".+")
    void actualFrontendRestRecoveryAndCacheUseThisServer() throws Exception {
        String frontend = System.getenv("CHAT_RECOVERY_FE_DIR");
        Path fixture = browserFixture(frontend);
        Process client = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("node", "scripts/runChatRecoveryIntegration.mjs");
            builder.directory(Path.of(frontend).toFile());
            builder.environment().put("CHAT_MULTI_CLIENT_CONFIG", fixture.toAbsolutePath().toString());
            builder.redirectErrorStream(true).redirectOutput(evidenceDirectory.resolve("frontend.log").toFile());
            client = builder.start();
            assertTrue(client.waitFor(60, TimeUnit.SECONDS), "frontend client did not finish");
            assertEquals(0, client.exitValue(), "inspect the isolated frontend test report");
            completed.add("actual-frontend-rest-recovery-masking-cache-reentry-absence");
        } finally {
            if (client != null && client.isAlive()) {
                client.destroyForcibly();
            }
            Files.deleteIfExists(fixture);
        }
    }

    private Path browserFixture(String frontend) throws Exception {
        Account owner = account();
        Account other = account();
        Path fixture = Path.of(frontend, "tests/fixtures/chatRecovery.local.json");
        Files.writeString(fixture, mapper.writeValueAsString(Map.of("url", "http://127.0.0.1:" + port,
                "token", owner.token(), "otherToken", other.token(), "room", room, "region", region)));
        return fixture;
    }

    @Test
    @Order(6)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_BROWSER_FE_DIR", matches = ".+")
    void provideOptionalBrowserFixtureOnTheSameIsolatedServer() throws Exception {
        String frontend = System.getenv("CHAT_BROWSER_FE_DIR");
        if (frontend == null) {
            return;
        }
        Path fixture = browserFixture(frontend);
        Path release = Path.of("build/chat-process-verification/browser-release");
        Files.deleteIfExists(release);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        while (!Files.exists(release) && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        Files.deleteIfExists(fixture);
        assertTrue(Files.exists(release), "browser validation must finish or explicitly release the isolated server");
        completed.add("browser-fixture-server-lifecycle");
    }

    @AfterEach
    void closeClients() {
        probes.forEach(probe -> probe.socket.abort());
        probes.clear();
    }

    @AfterAll
    void stopEnvironment() throws Exception {
        if (server != null && server.isAlive()) {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
        if (geography != null) {
            geography.stop(0);
        }
        Path report = evidenceDirectory.resolve("result.json");
        Files.createDirectories(report.getParent());
        String result = mapper.writeValueAsString(Map.of("mysql", "9.7.0", "server_starts", starts,
                "completed", completed, "fixture", "synthetic-only", "external_geography", "stub",
                "evidence_directory", evidenceDirectory.toString()));
        Files.writeString(report, result);
        Files.writeString(Path.of("build/chat-process-verification/result.json"), result);
    }

    private record Account(long id, String token, long member) {
    }

    private final class Probe implements WebSocket.Listener {
        private WebSocket socket;
        private JsonNode ready;
        private final LinkedBlockingQueue<String> control = new LinkedBlockingQueue<>();
        private final Map<String, LinkedBlockingQueue<JsonNode>> events = new ConcurrentHashMap<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        private Probe() {
            for (String type : List.of("CHAT_READY", "CHAT_MESSAGE", "CHAT_ACK", "CHAT_REJECTED")) {
                events.put(type, new LinkedBlockingQueue<>());
            }
        }

        private void send(String frame) {
            socket.sendText(frame + "\u0000", true).join();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String frame = text.toString();
                text.setLength(0);
                if (frame.startsWith("MESSAGE")) {
                    try {
                        JsonNode event = mapper
                                .readTree(frame.substring(frame.indexOf("\n\n") + 2).replace("\u0000", ""));
                        var queue = events.get(event.path("type").asText());
                        if (queue != null) {
                            queue.add(event.path("data"));
                        }
                    } catch (Exception invalid) {
                        closed.completeExceptionally(new AssertionError("invalid server event"));
                    }
                } else if (!frame.isBlank()) {
                    control.add(frame);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return onText(webSocket, StandardCharsets.UTF_8.decode(data), last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
            closed.complete(code);
            return null;
        }
    }
}
