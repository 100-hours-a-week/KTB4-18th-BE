package com.muse.coreauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
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
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.chat.auth.ChatAuthUnavailableException;
import com.muse.meomuneum.chat.auth.CoreSessionHttpClient;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.InternalSessionProtocol;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SessionPair;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/** Packaged Core/Chat authentication with optional shared participation and product message relay checks. */
@Testcontainers
class CoreAuthProcessTest {
    private static final boolean CONTROL = Boolean.getBoolean("auth.integration.control-events");
    private static final boolean RELAY = Boolean.getBoolean("auth.integration.message-relay");
    private static final boolean PARTICIPATION = Boolean.getBoolean("auth.integration.distributed-presence");
    private static final String SECRET = "auth-process-synthetic-jwt-secret-at-least-32";
    private static final String SERVICE = "auth-process-synthetic-service-secret-current";
    private static final String NEXT = "auth-process-synthetic-service-secret-next-key";
    private static final String REDIS_PASSWORD = "auth-process-redis-synthetic-password";
    private static final String PRODUCER = "core:" + UUID.randomUUID();
    private static final String ORIGIN = "http://localhost:5174";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("core_auth").withUsername("auth_test").withPassword("auth-test-only-db-password");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379)
            .withCopyToContainer(Transferable
                    .of("""
                            user default off
                            user fixture-admin on >%s +@all ~* &*
                            user core-auth on >%s &auth-test:chat:control:v1 \
                            +hello +ping +quit +client|setinfo +client|setname +publish
                            user chat-auth on >%s &auth-test:chat:control:v1 &auth-test:chat:room:* \
                            +hello +ping +quit +publish +client|setinfo +client|setname \
                            +subscribe +psubscribe +unsubscribe +punsubscribe \
                            ~auth-test:chat:state:* +eval +hget +hset +expire
                            """
                            .formatted(REDIS_PASSWORD, REDIS_PASSWORD, REDIS_PASSWORD)),
                    "/fixture/acl")
            .withCommand("redis-server", "--aclfile", "/fixture/acl", "--appendonly", "no", "--save", "");
    private final Path evidence = Path.of("build/core-auth-integration", "run-" + Instant.now().toEpochMilli());
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    private final List<Node> nodes = new ArrayList<>();
    private final List<Probe> probes = new ArrayList<>();
    private final AtomicInteger relayMode = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean blockNext = new java.util.concurrent.atomic.AtomicBoolean();

    @Test
    void realCoreRevocationOverTlsReachesChatsWithAndWithoutRedisAndOutagePreservesRecovery() throws Exception {
        Files.createDirectories(evidence);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:src/main/resources/db/migration").load().migrate();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("""
                INSERT INTO users(id,email,password_hash,nickname,role,created_at)
                VALUES (411001,'auth-process@example.test','unused-hash','authprobe','USER',UTC_TIMESTAMP(6))
                """);
        int hzPort = port();
        Config config = new Config().setClusterName("chat-auth-" + UUID.randomUUID());
        config.getNetworkConfig().setPort(hzPort).setPortAutoIncrement(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.logging.type", "slf4j");
        HazelcastInstance member = Hazelcast.newHazelcastInstance(config);
        HttpsServer relay = null;
        com.muse.meomuneum.chat.redis.ChatRedisConnection observer = null;
        var notifications = new LinkedBlockingQueue<String>();
        try (var relayWorkers = Executors.newVirtualThreadPerTaskExecutor()) {
            var sessions = member.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2");
            String revokedSid = UUID.randomUUID().toString();
            String survivingSid = UUID.randomUUID().toString();
            sessions.put(revokedSid, session(revokedSid));
            sessions.put(survivingSid, session(survivingSid));
            Node core = start("core", Map.ofEntries(
                    Map.entry("CORE_CHAT_AUTH_ENABLED", "true"), Map.entry("CHAT_AUTH_SERVICE_TOKEN_CURRENT", SERVICE),
                    Map.entry("CHAT_AUTH_SERVICE_TOKEN_NEXT", NEXT), Map.entry("AUTH_STATE_HAZELCAST_ENABLED", "true"),
                    Map.entry("AUTH_STATE_HAZELCAST_MEMBERS", "127.0.0.1:" + hzPort),
                    Map.entry("AUTH_STATE_HAZELCAST_CLUSTER_NAME", config.getClusterName()),
                    Map.entry("AUTH_BACKEND_REPLICA_COUNT", "1"),
                    Map.entry("AUTH_STATE_HMAC_KEY", "auth-process-synthetic-hmac-secret-at-least-32"),
                    Map.entry("AUTH_STATE_REPLAY_ENCRYPTION_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
                    Map.entry("CORE_AUTH_REDIS_ENABLED", "true"), Map.entry("CORE_AUTH_PRODUCER_ID", PRODUCER),
                    Map.entry("CORE_AUTH_REDIS_ENVIRONMENT", "auth-test"),
                    Map.entry("CORE_AUTH_REDIS_HOST", REDIS.getHost()),
                    Map.entry("CORE_AUTH_REDIS_PORT", REDIS.getMappedPort(6379).toString()),
                    Map.entry("CORE_AUTH_REDIS_USERNAME", "core-auth"),
                    Map.entry("CORE_AUTH_REDIS_PASSWORD", REDIS_PASSWORD),
                    Map.entry("CORE_AUTH_REDIS_SSL", "false")), null);
            Path trust = certificate();
            relay = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(trust)) {
                store.load(input, "synthetic-fixture-password".toCharArray());
            }
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, "synthetic-fixture-password".toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(keys.getKeyManagers(), null, null);
            relay.setHttpsConfigurator(new HttpsConfigurator(tls));
            relay.setExecutor(relayWorkers);
            relay.createContext("/", exchange -> {
                try {
                    if (relayMode.get() != 0 || (blockNext.get()
                            && ("Bearer " + NEXT).equals(exchange.getRequestHeaders().getFirst("Authorization")))) {
                        exchange.sendResponseHeaders(relayMode.get() == 0 ? 503 : relayMode.get(), -1);
                    } else {
                        byte[] body = exchange.getRequestBody().readNBytes(32769);
                        var request = HttpRequest
                                .newBuilder(URI.create(core.base() + "/internal/v1/auth/sessions/validate"))
                                .header("Authorization", exchange.getRequestHeaders().getFirst("Authorization"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                        var response = HttpClient.newHttpClient().send(request,
                                HttpResponse.BodyHandlers.ofByteArray());
                        exchange.getResponseHeaders().set("Cache-Control", "no-store");
                        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                        exchange.getResponseBody().write(response.body());
                    }
                } catch (Exception unavailable) {
                    // Canceled requests and shutdown are expected in this private TLS fixture.
                } finally {
                    exchange.close();
                }
            });
            relay.start();
            String https = "https://localhost:" + relay.getAddress().getPort();
            try (var untrusted = new CoreSessionHttpClient(https, SERVICE, false)) {
                assertThrows(ChatAuthUnavailableException.class,
                        () -> untrusted.validate(List.of(new SessionPair(411001, revokedSid))));
            }
            Map<String, String> chatEnv = new HashMap<>(
                    Map.of("CHAT_RUNTIME_ENABLED", "true", "CHAT_CORE_AUTH_ENABLED", "true",
                            "CORE_INTERNAL_BASE_URL", https, "CORE_AUTH_SERVICE_TOKEN", SERVICE));
            var wrongHost = new HashMap<>(chatEnv);
            wrongHost.put("CORE_INTERNAL_BASE_URL", "https://127.0.0.1:" + relay.getAddress().getPort());
            Node badPeer = start("chat", wrongHost, trust);
            assertEquals(503, request(badPeer, "GET", "/api/v1/regions/1/chat-room", token(revokedSid), null, Map.of())
                    .statusCode());
            stop(badPeer);
            if (PARTICIPATION) {
                chatEnv.putAll(Map.of("CHAT_DISTRIBUTED_PRESENCE_ENABLED", "true", "CHAT_REDIS_ENABLED", "true",
                        "CHAT_REDIS_ENVIRONMENT", "auth-test", "CHAT_REDIS_HOST", REDIS.getHost(),
                        "CHAT_REDIS_PORT", REDIS.getMappedPort(6379).toString(), "CHAT_REDIS_USERNAME", "chat-auth",
                        "CHAT_REDIS_PASSWORD", REDIS_PASSWORD, "CHAT_REDIS_SSL", "false"));
            }
            if (RELAY) {
                chatEnv.put("CHAT_MESSAGE_RELAY_ENABLED", "true");
                chatEnv.put("CHAT_MESSAGE_FINGERPRINT_KEYS", "v1:" + java.util.Base64.getEncoder().encodeToString(
                        "synthetic-message-fingerprint-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
            }
            if (CONTROL) {
                chatEnv.put("CHAT_CONTROL_EVENTS_ENABLED", "true");
            }
            Node polling = start("chat", chatEnv, trust);
            var fastEnv = new HashMap<>(chatEnv);
            fastEnv.put("CORE_AUTH_SERVICE_TOKEN", NEXT);
            fastEnv.putAll(Map.of("CHAT_REDIS_ENABLED", "true", "CHAT_REDIS_ENVIRONMENT", "auth-test",
                    "CHAT_REDIS_HOST", REDIS.getHost(), "CHAT_REDIS_PORT", REDIS.getMappedPort(6379).toString(),
                    "CHAT_REDIS_USERNAME", "chat-auth", "CHAT_REDIS_PASSWORD", REDIS_PASSWORD, "CHAT_REDIS_SSL",
                    "false",
                    "CHAT_AUTH_REDIS_ENABLED", "true", "CORE_AUTH_PRODUCER_IDS", PRODUCER));
            Node subscribed = start("chat", fastEnv, trust);
            await(() -> status(polling, "/actuator/health/readiness") == 200
                    && status(subscribed, "/actuator/health/readiness") == 200);
            var pair = new SessionPair(411001, revokedSid);
            for (String key : List.of(SERVICE, NEXT)) {
                var checked = internal(core, key, InternalSessionProtocol.encodeRequest(List.of(pair)));
                assertEquals(200, checked.statusCode(), checked.body());
                assertTrue(InternalSessionProtocol
                        .response(checked.body().getBytes(StandardCharsets.UTF_8), List.of(pair)).getFirst().active());
                assertFalse(checked.body().contains("hash"));
            }
            assertEquals(401, internal(core, token(revokedSid), new byte[0]).statusCode());
            assertEquals(413, internal(core, SERVICE, new byte[32769]).statusCode());
            assertEquals(400,
                    internal(core, SERVICE, InternalSessionProtocol.encodeRequest(List.of(pair, pair))).statusCode());
            long region = jdbc.queryForObject("SELECT id FROM regions WHERE code='41135'", Long.class);
            long room = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE region_id=?", Long.class, region);
            Probe first = join(PARTICIPATION ? subscribed : polling, polling, revokedSid, region, room);
            if (PARTICIPATION) {
                assertEquals(1, jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT user_id) FROM chat_view_runtime
                        WHERE environment='auth-test' AND state='CONNECTED'
                        """, Integer.class));
                // Start a Task after a live range exists; startup must leave it open.
                Node late = start("chat", chatEnv, trust);
                assertEquals(1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? AND ends_at IS NULL
                        """, Integer.class, first.view));
                stop(late);
            }
            Probe second = join(subscribed, revokedSid, region, room);
            Probe survivor = join(polling, survivingSid, region, room);
            if (CONTROL) {
                String controlSid = UUID.randomUUID().toString();
                sessions.put(controlSid, session(controlSid));
                Probe tab = join(subscribed, polling, controlSid, region, room);
                Probe peer = join(polling, subscribed, controlSid, region, room);
                assertEquals(tab.membership, peer.membership);
                var leave = request(subscribed, "DELETE", "/api/v1/chat-rooms/" + room + "/members/me?membership_id="
                        + tab.membership + "&view_session_id=" + tab.view, token(controlSid), null, Map.of());
                assertEquals(204, leave.statusCode(), leave.body());
                assertEquals(4100, tab.closed.get(5, TimeUnit.SECONDS));
                assertFalse(peer.closed.isDone());
                assertFalse(first.closed.isDone());
                assertFalse(survivor.closed.isDone());
                // Location evidence, handled on the opposite Task, terminates only the moving sid.
                var departure = request(polling, "POST", "/api/v1/chat-rooms/" + room
                        + "/region-departures?membership_id=" + peer.membership, token(controlSid),
                        json.writeValueAsString(Map.of("location_resolution_token", location(controlSid,
                                jdbc.queryForObject("SELECT id FROM regions WHERE code='41150'", Long.class)))),
                        Map.of());
                assertEquals(204, departure.statusCode(), departure.body());
                assertEquals(4100, peer.closed.get(5, TimeUnit.SECONDS));
                assertFalse(second.closed.isDone());
                assertFalse(survivor.closed.isDone());
                Probe replacement = join(subscribed, controlSid, region, room);
                assertTrue(replacement.membership != peer.membership);
                // All original terminal references may be delivered late; replacement must survive.
                var oldBodies = jdbc.queryForList("SELECT event_body FROM chat_message_outbox WHERE event_type IN "
                        + "('CHAT_VIEW_ENDED','CHAT_MEMBERSHIP_ENDED') ORDER BY room_seq DESC", String.class);
                try (var publish = new com.muse.meomuneum.chat.redis.ChatRedisConnection(
                        new com.muse.meomuneum.chat.redis.ChatRedisProperties("auth-test", REDIS.getHost(),
                                REDIS.getMappedPort(6379), "chat-auth", REDIS_PASSWORD, false,
                                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)))) {
                    for (String body : oldBodies) {
                        publish.commands().sync().publish("auth-test:chat:control:v1", body);
                        publish.commands().sync().publish("auth-test:chat:control:v1", body);
                    }
                }
                Thread.sleep(300);
                assertFalse(replacement.closed.isDone());
                await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE "
                        + "event_type='CHAT_VIEW_ENDED' AND status='SENT'", Integer.class) > 0
                        && jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE "
                                + "event_type='CHAT_MEMBERSHIP_ENDED' AND status='SENT'", Integer.class) > 0);
                Files.writeString(evidence.resolve("control-events.json"),
                        json.writeValueAsString(Map.of("crossTaskTabOnly", true, "sidRegionDeparture", true,
                                "otherSidSurvived", true, "lateDuplicate", true)));
                var examples = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                for (String type : List.of("CHAT_VIEW_ENDED", "CHAT_MEMBERSHIP_ENDED", "CHAT_PRESENCE_CHANGED")) {
                    examples.add(json.readTree(jdbc.queryForObject("SELECT event_body FROM chat_message_outbox "
                            + "WHERE event_type=? ORDER BY room_seq LIMIT 1", String.class, type)));
                }
                Files.writeString(evidence.resolve("control-event-examples.json"), json.writeValueAsString(examples));
            }
            var original = sessions.get(survivingSid);
            sessions.replace(survivingSid, original, original.withRotation("rotated-jti", "rotated-hash", "jti", "hash",
                    UUID.randomUUID().toString(), Instant.now().plusSeconds(60), "synthetic-encrypted-replay",
                    Instant.now().plusSeconds(3600), Instant.now().plusSeconds(3600)));
            survivor.sendMessage(room);
            String savedAck = survivor.await("CHAT_ACK");
            if (RELAY) {
                long message = frameData(savedAck).path("message").path("message_id").asLong();
                assertTrue(message > 0);
                for (Probe probe : List.of(first, second, survivor)) {
                    assertEquals(message, frameData(probe.await("CHAT_MESSAGE")).path("message_id").asLong());
                }
                Probe retrying = join(subscribed, survivingSid, region, room);
                retrying.sendMessage(room, survivor.lastClient, "인증 연동 검증");
                var retry = frameData(retrying.await("CHAT_ACK"));
                assertTrue(retry.path("deduplicated").asBoolean());
                assertEquals(message, retry.path("message").path("message_id").asLong());
                retrying.sendMessage(room, survivor.lastClient, "변경된 내용");
                assertEquals("CLIENT_ID_CONFLICT", frameData(retrying.await("CHAT_REJECTED")).path("reason").asText());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Integer.class));
                assertEquals(1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM chat_message_outbox WHERE event_type='CHAT_MESSAGE_CREATED'",
                                Integer.class));
                Files.writeString(evidence.resolve("message-relay.json"),
                        "{\"senderViaPubSub\":true,\"peerMessage\":true,\"crossTaskRetry\":true,\"conflict\":true}");
            }
            var csrf = request(core, "GET", "/api/v1/auth/token/csrf", null, null, Map.of());
            String csrfToken = json.readTree(csrf.body()).path("data").path("csrf_token").asText();
            var redisProperties = new com.muse.meomuneum.chat.redis.ChatRedisProperties("auth-test", REDIS.getHost(),
                    REDIS.getMappedPort(6379), "chat-auth", REDIS_PASSWORD, false,
                    java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
            observer = new com.muse.meomuneum.chat.redis.ChatRedisConnection(redisProperties);
            var subscription = observer.openSubscription(
                    new com.muse.meomuneum.chat.redis.ChatRedisChannels(redisProperties),
                    new io.lettuce.core.pubsub.RedisPubSubAdapter<>() {
                        @Override
                        public void message(String channel, String value) {
                            if (com.muse.meomuneum.chat.control.ChatControlEvent.isCoreAuth(value)) {
                                notifications.add(value);
                            }
                        }
                    });
            await(subscription::isReady);
            blockNext.set(true);
            Thread.sleep(1100); // Let every earlier HTTP validation deadline finish before revoking.
            long beforeRevoke = System.nanoTime();
            var logout = request(core, "POST", "/api/v1/auth/logout", token(revokedSid), null,
                    Map.of("X-CSRF-TOKEN", csrfToken));
            assertEquals(204, logout.statusCode(), logout.body());
            assertEquals(HazelcastAuthStateStore.SessionStatus.REVOKED, sessions.get(revokedSid).status());
            String notification = notifications.poll(2, TimeUnit.SECONDS);
            assertTrue(notification != null, "actual Core Redis publication required");
            var event = com.muse.meomuneum.global.security.AuthRevocationProtocol.decode(notification,
                    java.util.Set.of(PRODUCER));
            assertEquals(revokedSid, event.sid());
            assertEquals(sessions.get(revokedSid).generation(), event.generation());
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
            long revokeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeRevoke);
            assertTrue(revokeMillis < 5000);
            assertFalse(survivor.closed.isDone());
            assertEquals(401, request(polling, "GET", "/api/v1/regions/" + region + "/chat-room", token(revokedSid),
                    null, Map.of()).statusCode());
            relayMode.set(503);
            assertEquals(503, request(polling, "GET", "/api/v1/regions/" + region + "/chat-room", token(survivingSid),
                    null, Map.of()).statusCode());
            assertEquals(1013, survivor.closed.get(5, TimeUnit.SECONDS));
            await(() -> status(polling, "/actuator/health/readiness") == 503);
            relayMode.set(401);
            assertEquals(503, request(polling, "GET", "/api/v1/regions/" + region + "/chat-room", token(survivingSid),
                    null, Map.of()).statusCode());
            relayMode.set(0);
            assertEquals(200, request(polling, "GET", "/api/v1/regions/" + region + "/chat-room", token(survivingSid),
                    null, Map.of()).statusCode());
            await(() -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? AND ends_at IS NULL",
                    Integer.class, survivor.view) == 0);
            Probe recovered = connect(polling, survivingSid, room, survivor.membership, survivor.view);
            blockNext.set(false);
            var history = request(PARTICIPATION ? subscribed : polling, "GET",
                    "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                            + survivor.membership + "&mode=history&size=10",
                    token(survivingSid), null,
                    Map.of("X-Chat-View-Session", recovered.view));
            assertEquals(200, history.statusCode(), history.body());
            assertEquals(1, json.readTree(history.body()).path("data").path("items").size());
            if (PARTICIPATION) {
                var recovery = request(subscribed, "GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                        + survivor.membership + "&mode=recovery&size=10", token(survivingSid), null,
                        Map.of("X-Chat-View-Session", recovered.view));
                assertEquals(200, recovery.statusCode(), recovery.body());
                assertEquals(1, json.readTree(recovery.body()).path("data").path("items").size());
                String client = json.readTree(history.body()).path("data").path("items").get(0)
                        .path("client_message_id").asText();
                assertEquals(200, request(subscribed, "GET", "/api/v1/chat-rooms/" + room
                        + "/messages/by-client-id/" + client + "?membership_id=" + survivor.membership,
                        token(survivingSid), null, Map.of("X-Chat-View-Session", recovered.view)).statusCode());
                // A killed owner supplies no disconnect evidence: only its last checkpoint is readable.
                polling.process().destroyForcibly();
                assertTrue(polling.process().waitFor(5, TimeUnit.SECONDS));
                await(() -> "OFFLINE".equals(jdbc.queryForObject(
                        "SELECT state FROM chat_view_runtime WHERE view_id=?", String.class, recovered.view)));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM chat_view_intervals i
                        WHERE view_session_id=? AND end_reason='RESTART'
                            AND id=(SELECT MAX(latest.id) FROM chat_view_intervals latest
                                WHERE latest.view_session_id=i.view_session_id)
                            AND ends_at=verified_at AND end_message_id=verified_through_message_id
                            AND end_room_seq=verified_through_room_seq
                        """, Integer.class, recovered.view));
                assertEquals(403, request(subscribed, "GET", "/api/v1/chat-rooms/" + room
                        + "/messages?membership_id=" + survivor.membership + "&mode=history&size=10",
                        token(survivingSid), null, Map.of("X-Chat-View-Session", recovered.view)).statusCode());
                Probe reclaimed = connect(subscribed, survivingSid, room, survivor.membership, survivor.view);
                assertEquals(200, request(subscribed, "GET", "/api/v1/chat-rooms/" + room
                        + "/messages?membership_id=" + survivor.membership + "&mode=history&size=10",
                        token(survivingSid), null, Map.of("X-Chat-View-Session", reclaimed.view)).statusCode());
                if (RELAY) {
                    REDIS.execInContainer("redis-cli", "--user", "fixture-admin", "--pass", REDIS_PASSWORD,
                            "CLIENT", "KILL", "TYPE", "pubsub");
                    assertEquals("PONG", observer.commands().sync().ping());
                    assertEquals(1013, reclaimed.closed.get(5, TimeUnit.SECONDS));
                    await(() -> status(subscribed, "/actuator/health/readiness") == 503);
                    Files.writeString(evidence.resolve("pubsub-interruption.json"),
                            "{\"commandPing\":true,\"ws1013\":true,\"readinessDown\":true}");
                }
                Files.writeString(evidence.resolve("cross-task-read.json"),
                        "{\"history\":true,\"recovery\":true,\"byClientId\":true,\"killedOwnerReclaimed\":true}");
            }
            Files.writeString(evidence.resolve("result.json"),
                    json.writeValueAsString(Map.of("revocationMillis", revokeMillis,
                            "redisAndPolling", true, "redisWhileHttpBlocked", true, "tlsTrustAndHostname", true,
                            "otherSidSurvived", true,
                            "outageClose", 1013, "historyRecovered", true, "scope",
                            PARTICIPATION
                                    ? "isolated shared participation and auth; no message relay/load acceptance"
                                    : "isolated auth fanout; no distributed-state acceptance")));
        } finally {
            for (Probe probe : probes) {
                if (probe.socket != null) {
                    probe.socket.abort();
                }
            }
            for (Node node : nodes) {
                stop(node);
            }
            if (relay != null) {
                relay.stop(0);
            }
            if (observer != null) {
                observer.close();
            }
            member.shutdown();
        }
    }

    private Node start(String role, Map<String, String> additions, Path trust) throws Exception {
        var log = evidence.resolve(role + "-" + UUID.randomUUID() + ".log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString()));
        if (trust != null) {
            command.addAll(List.of("-Djavax.net.ssl.trustStore=" + trust,
                    "-Djavax.net.ssl.trustStorePassword=synthetic-fixture-password"));
        }
        command.addAll(
                List.of("-jar", System.getProperty("auth.integration." + role + "-jar"), "--server.address=127.0.0.1",
                        "--server.port=0", "--chat.message-cleanup.enabled=false"));
        if (role.equals("chat")) {
            command.add("--spring.profiles.active=chat-isolated");
        }
        var builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(Map.of(role.toUpperCase() + "_DB_URL", MYSQL.getJdbcUrl(),
                role.toUpperCase() + "_DB_USERNAME", MYSQL.getUsername(), role.toUpperCase() + "_DB_PASSWORD",
                MYSQL.getPassword(),
                "AUTH_JWT_SECRET", SECRET, "LOCATION_TOKEN_SECRET", SECRET, "AUTH_CORS_ALLOWED_ORIGINS", ORIGIN,
                "AUTH_COOKIE_SECURE", "false"));
        if (role.equals("core")) {
            builder.environment().putAll(Map.of("RECOMMENDATION_AI_BASE_URL", "http://127.0.0.1:1",
                    "SPEECH_TRANSCRIPTION_AI_BASE_URL", "http://127.0.0.1:1", "LOCATION_REVERSE_GEOCODING_BASE_URL",
                    "http://127.0.0.1:1"));
        }
        builder.environment().putAll(additions);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Node pending = new Node(builder.start(), 0);
        nodes.add(pending); // Cleanup owns the process even if startup fails before a port is assigned.
        int[] actualPort = {0};
        await(() -> {
            assertTrue(pending.process().isAlive(), "app exited; inspect core-auth-integration logs");
            try {
                var match = java.util.regex.Pattern.compile("Tomcat started on port ([0-9]+)")
                        .matcher(Files.readString(log));
                if (match.find()) {
                    actualPort[0] = Integer.parseInt(match.group(1));
                    return status(new Node(pending.process(), actualPort[0]), "/actuator/health/liveness") == 200;
                }
                return false;
            } catch (java.io.IOException unavailable) {
                return false;
            }
        });
        return new Node(pending.process(), actualPort[0]);
    }

    private Probe join(Node node, String sid, long region, long room) throws Exception {
        return join(node, node, sid, region, room);
    }

    private Probe join(Node node, Node socketNode, String sid, long region, long room) throws Exception {
        String location = location(sid, region);
        var response = request(node, "POST", "/api/v1/chat-rooms/" + room + "/members", token(sid),
                json.writeValueAsString(Map.of("location_resolution_token", location)), Map.of());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, response.body());
        return connect(socketNode, sid, room,
                json.readTree(response.body()).path("data").path("membership_id").asLong(),
                json.readTree(response.body()).path("data").path("view_session_id").asText());
    }

    private String location(String sid, long region) {
        var provider = new com.muse.meomuneum.location.security.LocationResolutionTokenProvider(
                new com.muse.meomuneum.location.config.LocationTokenProperties(SECRET), java.time.Clock.systemUTC());
        // Issue the existing token format from persisted administrative metadata, with synthetic signing material.
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        var parent = com.muse.meomuneum.chat.region.domain.Region.create("41", "fixture",
                com.muse.meomuneum.chat.region.domain.RegionLevel.SIDO, null);
        ReflectionTestUtils.setField(parent, "id",
                jdbc.queryForObject("SELECT parent_id FROM regions WHERE id=?", Long.class, region));
        var local = com.muse.meomuneum.chat.region.domain.Region.create(
                jdbc.queryForObject("SELECT code FROM regions WHERE id=?", String.class, region), "fixture",
                com.muse.meomuneum.chat.region.domain.RegionLevel.SIGUNGU, parent);
        ReflectionTestUtils.setField(local, "id", region);
        return provider.issueForSession(411001L, sid, parent, local, null).value();
    }

    private Probe connect(Node node, String sid, long room, long membership, String view) throws Exception {
        Probe probe = new Probe();
        probe.membership = membership;
        probes.add(probe);
        probe.socket = http.newWebSocketBuilder().header("Origin", ORIGIN).subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://127.0.0.1:" + node.port() + "/ws"), probe).get(10, TimeUnit.SECONDS);
        probe.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token(sid)
                + "\nroom_id:" + room + "\nmembership_id:" + membership
                + (view == null ? "" : "\nview_session_id:" + view + "\nlast_message_id:0") + "\n\n");
        probe.await("CONNECTED");
        probe.send("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + room + "\nreceipt:room\n\n");
        probe.await("RECEIPT");
        probe.send("SUBSCRIBE\nid:events\ndestination:/user/queue/chat-events\nreceipt:events\n\n");
        String frame = probe.await("CHAT_READY");
        probe.view = json.readTree(frame.substring(frame.indexOf("\n\n") + 2).replace("\u0000", ""))
                .path("data").path("view_session_id").asText();
        return probe;
    }

    private HttpResponse<String> internal(Node core, String credential, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(core.base() + "/internal/v1/auth/sessions/validate"))
                .header("Authorization", "Bearer " + credential).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(Node node, String method, String path, String bearer, String body,
            Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(node.base() + path)).timeout(java.time.Duration.ofSeconds(5))
                .header("Origin", ORIGIN).header("Content-Type", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(builder::header);
        return http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(Node node, String path) {
        try {
            return request(node, "GET", path, null, null, Map.of()).statusCode();
        } catch (Exception unavailable) {
            return 0;
        }
    }

    private String token(String sid) {
        User user = new User() {
        };
        ReflectionTestUtils.setField(user, "id", 411001L);
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        return new JwtTokenProvider(new JwtProperties("project-api", "project-api", SECRET, 3600, 1209600))
                .createAccessToken(user, sid);
    }

    private HazelcastAuthStateStore.AuthSession session(String sid) {
        Instant now = Instant.now();
        return new HazelcastAuthStateStore.AuthSession(411001L, sid, "fixture",
                HazelcastAuthStateStore.SessionStatus.ACTIVE,
                "jti", "hash", null, null, null, null, null, null, null, 0, null, null, 0,
                now, now, now.plusSeconds(3600), now.plusSeconds(3600), now.plusSeconds(7200), null);
    }

    private Path certificate() throws Exception {
        Path store = evidence.resolve("fixture.p12").toAbsolutePath();
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-ext", "bc=ca:true", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "synthetic-fixture-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(evidence.resolve("keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue());
        return store;
    }

    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("integration deadline exceeded; inspect evidence");
    }

    private com.fasterxml.jackson.databind.JsonNode frameData(String frame) throws Exception {
        int start = frame.indexOf("\n\n");
        return json.readTree(frame.substring(start + 2).replace("\u0000", "")).path("data");
    }

    private int port() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
    private void stop(Node node) throws Exception {
        if (node.process().isAlive()) {
            node.process().destroy();
            if (!node.process().waitFor(15, TimeUnit.SECONDS)) {
                node.process().destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
    private record Node(Process process, int port) {
        String base() {
            return "http://127.0.0.1:" + port;
        }
    }
    private final class Probe implements WebSocket.Listener {
        private WebSocket socket;
        private long membership;
        private String view;
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        private final List<String> pending = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        void send(String value) {
            socket.sendText(value + "\u0000", true).join();
        }
        private String lastClient;
        void sendMessage(long room) throws Exception {
            sendMessage(room, UUID.randomUUID().toString(), "인증 연동 검증");
        }
        void sendMessage(long room, String client, String content) throws Exception {
            lastClient = client;
            send("SEND\ndestination:/app/chat-rooms/" + room + "/messages\ncontent-type:application/json\n\n"
                    + json.writeValueAsString(Map.of("client_message_id", client, "content", content)));
        }
        String await(String wanted) throws Exception {
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).contains(wanted)) {
                    return pending.remove(i);
                }
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end) {
                String frame = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if (frame.contains(wanted)) {
                        return frame;
                    }
                    pending.add(frame);
                }
            }
            throw new AssertionError("missing frame " + wanted);
        }
        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence value, boolean last) {
            text.append(value);
            if (last) {
                incoming.add(text.toString());
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer value, boolean last) {
            return onText(socket, StandardCharsets.UTF_8.decode(value), last);
        }
        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(code);
            return null;
        }
    }
}
