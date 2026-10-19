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
    private final List<Map<String, Object>> participationRestarts = new ArrayList<>();
    private JdbcTemplate jdbc;
    private HttpServer geography;
    private Process server;
    private int port;
    private long room;
    private long region;
    private int starts;
    private volatile String geographyCode = "4113510100";

    @BeforeAll
    void startEnvironment() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC",
                MYSQL.getUsername(), MYSQL.getPassword()));
        geography = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        geography.createContext("/", exchange -> {
            byte[] body = ("{\"documents\":[{\"region_type\":\"B\",\"code\":\"" + geographyCode + "\"}]}")
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
        return session(id, room, "4113510100");
    }

    private Account session(long id, long targetRoom, String regionCode) throws Exception {
        Instant now = Instant.now();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("project-api").subject(Long.toString(id)).audience("project-api")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("typ", "access").claim("roles", List.of("USER")).claim("sid", UUID.randomUUID().toString())
                .build());
        signed.sign(new MACSigner(SECRET));
        String token = signed.serialize();
        var join = join(token, targetRoom, regionCode);
        assertEquals(201, join.statusCode());
        return new Account(id, token, mapper.readTree(join.body()).path("data").path("membership_id").asLong());
    }

    private HttpResponse<String> join(String token, long targetRoom, String regionCode) throws Exception {
        geographyCode = regionCode;
        try {
            var location = request("POST", "/api/v1/locations/resolve", token, null,
                    "{\"latitude\":37.3595704,\"longitude\":127.105399,\"accuracy_meters\":10}");
            assertEquals(200, location.statusCode(), "synthetic location resolution failed");
            String locationToken = mapper.readTree(location.body()).path("data")
                    .path("location_resolution_token").asText();
            return request("POST", "/api/v1/chat-rooms/" + targetRoom + "/members", token, null,
                    mapper.writeValueAsString(Map.of("location_resolution_token", locationToken)));
        } finally {
            geographyCode = "4113510100";
        }
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
        return connect(account, view, last, room);
    }

    private Probe connect(Account account, String view, long last, long targetRoom) throws Exception {
        Probe probe = new Probe();
        probes.add(probe);
        probe.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5174")
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), probe)
                .get(10, TimeUnit.SECONDS);
        probe.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + account.token()
                + "\nroom_id:" + targetRoom + "\nmembership_id:" + account.member()
                + (view == null ? "" : "\nview_session_id:" + view + "\nlast_message_id:" + last) + "\n\n");
        assertTrue(probe.control.poll(10, TimeUnit.SECONDS).startsWith("CONNECTED"));
        probe.send("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + targetRoom + "\nreceipt:room\n\n");
        assertTrue(probe.control.poll(10, TimeUnit.SECONDS).startsWith("RECEIPT"));
        probe.send("SUBSCRIBE\nid:events\ndestination:/user/queue/chat-events\nreceipt:events\n\n");
        assertTrue(probe.control.poll(10, TimeUnit.SECONDS).startsWith("RECEIPT"));
        probe.ready = probe.events.get("CHAT_READY").poll(10, TimeUnit.SECONDS);
        assertTrue(probe.ready != null, "actual subscription readiness is required");
        return probe;
    }

    private JsonNode send(Probe probe, String clientId, String text) throws Exception {
        return send(probe, clientId, text, room);
    }

    private JsonNode send(Probe probe, String clientId, String text, long targetRoom) throws Exception {
        probe.send("SEND\ndestination:/app/chat-rooms/" + targetRoom + "/messages\ncontent-type:application/json\n\n"
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
                + owner.member() + "&view_session_id=" + first.ready.path("view_session_id").asText(),
                owner.token(), null, null).statusCode());
        assertTrue(List.of(200, 403).contains(racing.get(10, TimeUnit.SECONDS)));
        assertEquals(4100, first.closed.get(10, TimeUnit.SECONDS));
        assertFalse(device.closed.isDone());
        assertEquals(200, query(owner, device, null, 50).statusCode());
        assertEquals(204, request("DELETE", "/api/v1/chat-rooms/" + room + "/members/me?membership_id="
                + owner.member() + "&view_session_id=" + device.ready.path("view_session_id").asText(),
                owner.token(), null, null).statusCode());
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
        completed.add("concurrent-tab-departure-ban-retention-own-lookup");
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
        Probe exited = connect(owner, null, 0);
        String exitedView = exited.ready.path("view_session_id").asText();
        assertEquals(204, request("DELETE", "/api/v1/chat-rooms/" + room + "/members/me?membership_id="
                + owner.member() + "&view_session_id=" + exitedView, owner.token(), null, null).statusCode());
        assertEquals(4100, exited.closed.get(10, TimeUnit.SECONDS));
        assertFalse(first.closed.isDone());
        long oldPid = server.pid();
        server.destroyForcibly();
        assertTrue(server.waitFor(10, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
        jdbc.update("INSERT INTO chat_room_messages (room_id,user_id,client_message_id,content,created_at) "
                + "VALUES (?,?,?,'검증 확인 부재',UTC_TIMESTAMP(6))", room, owner.id(), UUID.randomUUID().toString());
        startServer();
        assertNotEquals(oldPid, server.pid());
        assertEquals(403, query(owner, exited, null, 50).statusCode());
        Probe endedReplay = new Probe();
        probes.add(endedReplay);
        endedReplay.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5174")
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), endedReplay)
                .get(10, TimeUnit.SECONDS);
        endedReplay.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + owner.token()
                + "\nroom_id:" + room + "\nmembership_id:" + owner.member()
                + "\nview_session_id:" + exitedView + "\n\n");
        assertEquals(4103, endedReplay.closed.get(10, TimeUnit.SECONDS));
        assertTrue(endedReplay.events.get("CHAT_READY").isEmpty());
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
        completed.add("forced-jvm-restart-checkpoint-boundary-ended-and-missing-view");
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

    @Test
    @Order(7)
    void restartPreservesIndependentSessionsRegionsAndAccountBasedCapacity() throws Exception {
        Account owner = account();
        Account outsider = account();
        long otherRoom = jdbc.queryForObject("SELECT r.id FROM chat_rooms r JOIN regions g ON g.id=r.region_id "
                + "WHERE g.code='11680'", Long.class);
        Probe endedTab = connect(owner, issuedView(owner), 0);
        Probe tab = connect(owner, null, 0);
        Probe sibling = connect(owner, null, 0);
        Account device = session(owner.id(), room, "4113510100");
        Probe deviceTab = connect(device, issuedView(device), 0);
        Account regional = session(owner.id(), otherRoom, "1168010100");
        Probe regionTab = connect(regional, issuedView(regional), 0, otherRoom);
        assertNotEquals(owner.member(), device.member());
        assertNotEquals(device.member(), regional.member());
        JsonNode first = send(tab, UUID.randomUUID().toString(), "account restart first");
        JsonNode second = send(deviceTab, UUID.randomUUID().toString(), "account restart device");
        JsonNode regionalMessage = send(regionTab, UUID.randomUUID().toString(), "account other region", otherRoom);
        history(owner, tab, room, null, 50);
        history(regional, regionTab, otherRoom, null, 50);
        leave(owner, endedTab, room);
        List<String> activeViews = List.of(view(tab), view(sibling), view(deviceTab), view(regionTab));
        Map<String, Long> checkpoints = new java.util.HashMap<>();
        for (String id : activeViews) {
            checkpoints.put(id, jdbc.queryForObject("SELECT verified_through_message_id FROM chat_view_intervals "
                    + "WHERE view_session_id=? AND ends_at IS NULL", Long.class, id));
        }
        String hidden = UUID.randomUUID().toString();
        restartWithUnconfirmedMessage(owner.id(), room, hidden);
        for (String id : activeViews) {
            assertEquals(checkpoints.get(id), jdbc.queryForObject("SELECT end_message_id FROM chat_view_intervals "
                    + "WHERE view_session_id=? AND end_reason='RESTART'", Long.class, id));
        }
        rejectReplay(owner, view(endedTab), room);
        jdbc.update("UPDATE chat_rooms SET capacity=1 WHERE id=?", room);
        try {
            Probe restored = connect(owner, view(tab), 0);
            Probe restoredSibling = connect(owner, view(sibling), 0);
            Probe restoredDevice = connect(device, view(deviceTab), 0);
            Probe restoredRegion = connect(regional, view(regionTab), 0, otherRoom);
            assertConnectedCount(restored, 1);
            assertConnectedCount(restoredSibling, 1);
            assertConnectedCount(restoredDevice, 1);
            assertConnectedCount(restoredRegion, 1);
            assertEquals(409, join(outsider.token(), room, "4113510100").statusCode());
            assertEquals(List.of(second.path("message_id").asLong(), first.path("message_id").asLong()),
                    messageIds(history(owner, restored, room, null, 50)));
            assertEquals(List.of(regionalMessage.path("message_id").asLong()),
                    messageIds(history(regional, restoredRegion, otherRoom, null, 50)));
            assertFalse(history(device, restoredDevice, room, null, 50).path("items").toString().contains(hidden));
            assertEquals(403, request("GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                    + device.member(), owner.token(), view(restoredDevice), null).statusCode());
            String lookup = "/api/v1/chat-rooms/" + room + "/messages/by-client-id/" + hidden
                    + "?membership_id=" + owner.member();
            assertEquals(404, request("GET", lookup, owner.token(), view(restored), null).statusCode());
            leave(owner, restored, room);
            assertFalse(restoredSibling.closed.isDone());
            send(restoredSibling, UUID.randomUUID().toString(), "same session sibling after restart");
            leave(owner, restoredSibling, room);
            assertFalse(restoredDevice.closed.isDone());
            assertFalse(restoredRegion.closed.isDone());
            send(restoredDevice, UUID.randomUUID().toString(), "remaining device after restart");
            send(restoredRegion, UUID.randomUUID().toString(), "remaining region after restart", otherRoom);
            completed.add("account-restart-multi-sid-multi-region-tab-checkpoints-account-capacity");
        } finally {
            jdbc.update("UPDATE chat_rooms SET capacity=25 WHERE id=?", room);
        }
    }

    @Test
    @Order(8)
    void restartKeepsEndedParticipationHistoryButRejectsMissingExpiredAndForeignContexts() throws Exception {
        Account ended = account();
        Probe old = connect(ended, issuedView(ended), 0);
        JsonNode past = send(old, UUID.randomUUID().toString(), "ended session history");
        leave(ended, old, room);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_members WHERE id=? "
                + "AND deleted_at IS NOT NULL", Integer.class, ended.member()));
        Account current = session(ended.id(), room, "4113510100");
        Probe valid = connect(current, issuedView(current), 0);
        Probe missing = connect(current, null, 0);
        Probe expired = connect(current, null, 0);
        JsonNode proven = send(valid, UUID.randomUUID().toString(), "new session checkpoint");
        JsonNode snapshot = history(current, valid, room, null, 1);
        String olderCursor = snapshot.path("next_cursor").asText();
        assertTrue(snapshot.path("has_next").asBoolean());
        long oldPid = server.pid();
        server.destroyForcibly();
        assertTrue(server.waitFor(10, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
        jdbc.update("DELETE FROM chat_view_sessions WHERE id=?", view(missing));
        jdbc.update("UPDATE chat_view_sessions SET updated_at=UTC_TIMESTAMP(6)-INTERVAL 25 HOUR WHERE id=?",
                view(expired));
        String hidden = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_room_messages (room_id,user_id,client_message_id,content,created_at) "
                + "VALUES (?,?,?,'downtime gap',UTC_TIMESTAMP(6))", room, current.id(), hidden);
        startServer();
        assertNotEquals(oldPid, server.pid());
        participationRestarts.add(Map.of("scenario", "ended-participation-history", "old_pid", oldPid,
                "new_pid", server.pid(), "same_database", true));
        rejectInactiveMembership(ended, view(old));
        rejectReplay(current, view(missing), room);
        rejectReplay(current, view(expired), room);
        Probe restored = connect(current, view(valid), 0);
        assertEquals(List.of(past.path("message_id").asLong()),
                messageIds(history(current, restored, room, olderCursor, 50)));
        assertEquals(List.of(proven.path("message_id").asLong()), messageIds(page(current, restored, null, 50)));
        Account relogged = session(ended.id(), room, "4113510100");
        Probe fresh = connect(relogged, issuedView(relogged), 0);
        assertTrue(page(relogged, fresh, null, 50).path("items").isEmpty());
        assertEquals(List.of(proven.path("message_id").asLong(), past.path("message_id").asLong()),
                messageIds(history(relogged, fresh, room, null, 50)));
        assertEquals(403, request("GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                + current.member(), relogged.token(), view(restored), null).statusCode());
        assertEquals(400, request("GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                + relogged.member() + "&cursor=" + olderCursor, relogged.token(), view(fresh), null).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_members WHERE id=? "
                + "AND deleted_at IS NOT NULL", Integer.class, ended.member()));
        send(fresh, UUID.randomUUID().toString(), "fresh session live after restart");
        completed.add("account-restart-ended-history-new-sid-cursor-missing-expired-view-ownership");
    }

    private String issuedView(Account account) {
        return jdbc.queryForObject("SELECT id FROM chat_view_sessions WHERE membership_id=? "
                + "ORDER BY created_at LIMIT 1", String.class, account.member());
    }

    private List<Long> messageIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("message_id").asLong()));
        return List.copyOf(ids);
    }

    @AfterEach
    void closeClients() {
        probes.forEach(probe -> probe.socket.abort());
        probes.clear();
    }

    private String view(Probe probe) {
        return probe.ready.path("view_session_id").asText();
    }

    private JsonNode history(Account owner, Probe probe, long targetRoom, String cursor, int size) throws Exception {
        var response = request("GET", "/api/v1/chat-rooms/" + targetRoom + "/messages?membership_id=" + owner.member()
                + "&mode=history&size=" + size + (cursor == null ? "" : "&cursor=" + cursor),
                owner.token(), view(probe), null);
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body()).path("data");
    }

    private void leave(Account owner, Probe probe, long targetRoom) throws Exception {
        assertEquals(204, request("DELETE", "/api/v1/chat-rooms/" + targetRoom + "/members/me?membership_id="
                + owner.member() + "&view_session_id=" + view(probe), owner.token(), null, null).statusCode());
        assertEquals(4100, probe.closed.get(10, TimeUnit.SECONDS));
    }

    private void assertConnectedCount(Probe probe, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            JsonNode event = probe.events.get("CHAT_PRESENCE").poll(1, TimeUnit.SECONDS);
            if (event != null && event.path("connected_count").asInt(-1) == expected) {
                return;
            }
        }
        throw new AssertionError("expected account-based connected count was not observed");
    }

    private long restartWithUnconfirmedMessage(long userId, long targetRoom, String clientId) throws Exception {
        long oldPid = server.pid();
        server.destroyForcibly();
        assertTrue(server.waitFor(10, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
        jdbc.update("INSERT INTO chat_room_messages (room_id,user_id,client_message_id,content,created_at) "
                + "VALUES (?,?,?,'unconfirmed during restart',UTC_TIMESTAMP(6))", targetRoom, userId, clientId);
        startServer();
        assertNotEquals(oldPid, server.pid());
        participationRestarts.add(Map.of("scenario", "multi-session-regions", "old_pid", oldPid,
                "new_pid", server.pid(), "same_database", true));
        return oldPid;
    }

    private void rejectInactiveMembership(Account owner, String viewId) throws Exception {
        assertEquals(403, request("GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                + owner.member(), owner.token(), viewId, null).statusCode());
        Probe rejected = new Probe();
        probes.add(rejected);
        rejected.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5174")
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), rejected)
                .get(10, TimeUnit.SECONDS);
        rejected.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + owner.token()
                + "\nroom_id:" + room + "\nmembership_id:" + owner.member()
                + "\nview_session_id:" + viewId + "\n\n");
        String error = rejected.control.poll(10, TimeUnit.SECONDS);
        assertTrue(error != null && error.startsWith("ERROR") && error.contains("message:CHAT_LEFT"));
        assertTrue(rejected.events.get("CHAT_READY").isEmpty());
    }

    private void rejectReplay(Account owner, String viewId, long targetRoom) throws Exception {
        assertEquals(403, request("GET", "/api/v1/chat-rooms/" + targetRoom + "/messages?membership_id="
                + owner.member(), owner.token(), viewId, null).statusCode());
        Probe rejected = new Probe();
        probes.add(rejected);
        rejected.socket = http.newWebSocketBuilder().header("Origin", "http://localhost:5174")
                .subprotocols("v12.stomp").buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), rejected)
                .get(10, TimeUnit.SECONDS);
        rejected.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + owner.token()
                + "\nroom_id:" + targetRoom + "\nmembership_id:" + owner.member()
                + "\nview_session_id:" + viewId + "\n\n");
        assertEquals(4103, rejected.closed.get(10, TimeUnit.SECONDS));
        assertTrue(rejected.events.get("CHAT_READY").isEmpty());
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
                "completed", completed, "participation_restarts", participationRestarts,
                "fixture", "synthetic-only", "external_geography", "stub",
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
            for (String type : List.of("CHAT_READY", "CHAT_MESSAGE", "CHAT_ACK", "CHAT_REJECTED", "CHAT_PRESENCE")) {
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
