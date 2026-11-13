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
    private static final boolean PRODUCTION = Boolean.getBoolean("auth.integration.production");
    private static final ProductionTlsFixture TLS = PRODUCTION ? new ProductionTlsFixture() : null;
    private static final boolean MUSIC = Boolean.getBoolean("auth.integration.music-events");
    private static final boolean RUNTIME = Boolean.getBoolean("auth.integration.runtime");
    private static final String METRICS = "synthetic-metrics-service-token-at-least-32";
    private static final boolean CONTROL = Boolean.getBoolean("auth.integration.control-events");
    private static final boolean RELAY = Boolean.getBoolean("auth.integration.message-relay");
    private static final boolean PARTICIPATION = Boolean.getBoolean("auth.integration.distributed-presence");
    private static final String SECRET = "auth-process-synthetic-jwt-secret-at-least-32";
    private static final String HMAC_KEY = "auth-process-synthetic-hmac-secret-at-least-32";
    private static final String LOCATION = PRODUCTION
            ? "synthetic-distinct-location-signing-key-at-least-32"
            : SECRET;
    private static final String SERVICE = "auth-process-synthetic-service-secret-current";
    private static final String NEXT = "auth-process-synthetic-service-secret-next-key";
    private static final String REDIS_PASSWORD = "auth-process-redis-synthetic-password";
    private static final int REDIS_PORT = redisPort();
    private static final String PRODUCER = "core:" + UUID.randomUUID();
    private static final String ORIGIN = PRODUCTION ? "https://web.example.test" : "http://localhost:5174";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("core_auth").withUsername("auth_test").withPassword("auth-test-only-db-password");
    @Container
    private static final GenericContainer<?> REDIS = redisContainer();

    private static GenericContainer<?> redisContainer() {
        var container = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new com.github.dockerjava.api.model.PortBinding(
                                com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", REDIS_PORT),
                                new com.github.dockerjava.api.model.ExposedPort(6379))))
                .withCopyToContainer(Transferable
                        .of("""
                                user default off
                                user fixture-admin on >%s +@all ~* &*
                                user core-auth on >%s &auth-test:chat:control:v1 \
                                +hello +ping +quit +client|setinfo +client|setname +publish
                                user chat-auth on >%s &auth-test:chat:control:v1 &auth-test:chat:room:* \
                                &auth-test:chat:music:* \
                                +hello +ping +quit +publish +info +client|setinfo +client|setname \
                                +subscribe +psubscribe +unsubscribe +punsubscribe \
                                ~auth-test:chat:state:* +eval +hget +hset +expire
                                """
                                .formatted(REDIS_PASSWORD, REDIS_PASSWORD, REDIS_PASSWORD)),
                        "/fixture/acl")
                .withCommand("redis-server", "--aclfile", "/fixture/acl", "--appendonly", "no", "--save", "");
        if (PRODUCTION) {
            container.withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(TLS.certificate()),
                    "/fixture/redis.crt")
                    .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(TLS.privateKey()),
                            "/fixture/redis.key")
                    .withCommand("redis-server", "--aclfile", "/fixture/acl", "--appendonly", "no", "--save", "",
                            "--port", "0", "--tls-port", "6379", "--tls-cert-file", "/fixture/redis.crt",
                            "--tls-key-file", "/fixture/redis.key", "--tls-ca-cert-file", "/fixture/redis.crt",
                            "--tls-auth-clients", "no");
        }
        return container;
    }
    private final Path evidence = Path.of(
            PRODUCTION ? "build/chat-production-integration" : "build/core-auth-integration",
            "run-" + Instant.now().toEpochMilli());
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    private final List<Node> nodes = new ArrayList<>();
    private final List<Probe> probes = new ArrayList<>();
    private final Map<String, String> issuedTokens = new HashMap<>();
    private final Map<String, BrowserSession> browsers = new HashMap<>();
    private String productionCoreBase;

    private record BrowserSession(HttpClient client, String flowId, long epoch, String csrf) {
    }

    private final AtomicInteger relayMode = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean blockNext = new java.util.concurrent.atomic.AtomicBoolean();

    @Test
    void realCoreRevocationOverTlsReachesChatsWithAndWithoutRedisAndOutagePreservesRecovery() throws Exception {
        Files.createDirectories(evidence);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:src/main/resources/db/migration").load().migrate();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        if (PRODUCTION) {
            prepareDirectAuthAuthority(jdbc);
        }
        jdbc.update("""
                INSERT INTO users(id,email,password_hash,nickname,role,created_at)
                VALUES (411001,'auth-process@example.test',?,'authprobe','USER',UTC_TIMESTAMP(6))
                """, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("synthetic-login-password"));
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
            String flowCookie = UUID.randomUUID().toString();
            String flowId = null;
            if (!PRODUCTION) {
                sessions.put(revokedSid, session(revokedSid));
                sessions.put(survivingSid, session(survivingSid));
                flowId = seedBrowserSession(jdbc, revokedSid, flowCookie);
                seedBrowserSession(jdbc, survivingSid, UUID.randomUUID().toString());
            }
            Node core = start("core", Map.ofEntries(
                    Map.entry("CORE_CHAT_AUTH_ENABLED", "true"), Map.entry("CHAT_AUTH_SERVICE_TOKEN_CURRENT", SERVICE),
                    Map.entry("CHAT_AUTH_SERVICE_TOKEN_NEXT", NEXT), Map.entry("AUTH_STATE_HAZELCAST_ENABLED", "true"),
                    Map.entry("AUTH_STATE_HAZELCAST_MEMBERS", "127.0.0.1:" + hzPort),
                    Map.entry("AUTH_STATE_HAZELCAST_CLUSTER_NAME", config.getClusterName()),
                    Map.entry("AUTH_BACKEND_REPLICA_COUNT", "1"),
                    Map.entry("AUTH_STATE_HMAC_KEY", HMAC_KEY),
                    Map.entry("AUTH_MUTATION_GATE_OPEN", "true"),
                    Map.entry("AUTH_STATE_REPLAY_ENCRYPTION_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
                    Map.entry("CORE_AUTH_REDIS_ENABLED", "true"), Map.entry("CORE_AUTH_PRODUCER_ID", PRODUCER),
                    Map.entry("CORE_AUTH_REDIS_ENVIRONMENT", "auth-test"),
                    Map.entry("CORE_AUTH_REDIS_HOST", REDIS.getHost()),
                    Map.entry("CORE_AUTH_REDIS_PORT", REDIS.getMappedPort(6379).toString()),
                    Map.entry("CORE_AUTH_REDIS_USERNAME", "core-auth"),
                    Map.entry("CORE_AUTH_REDIS_PASSWORD", REDIS_PASSWORD),
                    Map.entry("CORE_AUTH_REDIS_SSL", Boolean.toString(PRODUCTION)),
                    Map.entry("CORE_AUTH_REDIS_CA_CERTIFICATE", PRODUCTION ? TLS.certificate().toString() : "")), null);
            Path trust = PRODUCTION ? TLS.coreStore() : certificate();
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
                    if (PRODUCTION) {
                        proxyProduction(exchange, core);
                        return;
                    }
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
            String https = "https://" + (PRODUCTION ? ProductionTlsFixture.CORE_HOST : "localhost") + ":"
                    + relay.getAddress().getPort();
            try (var untrusted = new CoreSessionHttpClient(https, SERVICE, false)) {
                assertThrows(ChatAuthUnavailableException.class,
                        () -> untrusted.validate(List.of(new SessionPair(411001, revokedSid))));
            }
            Map<String, String> chatEnv = new HashMap<>(
                    Map.of("CHAT_RUNTIME_ENABLED", "true", "CHAT_CORE_AUTH_ENABLED", "true",
                            "CORE_INTERNAL_BASE_URL", https, "CORE_AUTH_SERVICE_TOKEN", SERVICE));
            productionCoreBase = https;
            if (!PRODUCTION) {
                var wrongHost = new HashMap<>(chatEnv);
                wrongHost.put("CORE_INTERNAL_BASE_URL", "https://127.0.0.1:" + relay.getAddress().getPort());
                Node badPeer = start("chat", wrongHost, trust);
                assertEquals(503,
                        request(badPeer, "GET", "/api/v1/regions/1/chat-room", token(revokedSid), null, Map.of())
                                .statusCode());
                stop(badPeer);
            }
            if (PARTICIPATION) {
                chatEnv.putAll(Map.of("CHAT_DISTRIBUTED_PRESENCE_ENABLED", "true", "CHAT_REDIS_ENABLED", "true",
                        "CHAT_REDIS_ENVIRONMENT", "auth-test", "CHAT_REDIS_HOST", REDIS.getHost(),
                        "CHAT_REDIS_PORT", REDIS.getMappedPort(6379).toString(), "CHAT_REDIS_USERNAME", "chat-auth",
                        "CHAT_REDIS_PASSWORD", REDIS_PASSWORD, "CHAT_REDIS_SSL", Boolean.toString(PRODUCTION)));
            }
            if (RELAY) {
                chatEnv.put("CHAT_MESSAGE_RELAY_ENABLED", "true");
                chatEnv.put("CHAT_MESSAGE_FINGERPRINT_KEYS", "v1:" + java.util.Base64.getEncoder().encodeToString(
                        "synthetic-message-fingerprint-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
            }
            if (CONTROL) {
                chatEnv.put("CHAT_CONTROL_EVENTS_ENABLED", "true");
            }
            if (MUSIC) {
                chatEnv.put("CHAT_MUSIC_QUEUE_ENABLED", "true");
                chatEnv.put("CHAT_MUSIC_PLAYBACK_ENABLED", "true");
                chatEnv.put("CHAT_MUSIC_EVENTS_ENABLED", "true");
                chatEnv.put("CHAT_MUSIC_QUEUE_COMMAND_INTERVAL_MILLIS", "0");
                chatEnv.put("CHAT_MUSIC_KEYS", "fixture:" + java.util.Base64.getEncoder().encodeToString(
                        "synthetic-music-fingerprint-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
            }
            if (PRODUCTION) {
                chatEnv.putAll(Map.of("CHAT_AUTH_REDIS_ENABLED", "true", "CORE_AUTH_PRODUCER_IDS", PRODUCER,
                        "CHAT_REDIS_SSL_CA_CERTIFICATE", TLS.certificate().toString()));
            }
            if (RUNTIME) {
                recoveryCommand();
                chatEnv.put("CHAT_LIFECYCLE_ENABLED", "true");
                chatEnv.put("CHAT_METRICS_SERVICE_TOKEN", METRICS);
            }
            Node polling = start("chat", chatEnv, trust);
            var fastEnv = new HashMap<>(chatEnv);
            fastEnv.put("CORE_AUTH_SERVICE_TOKEN", NEXT);
            fastEnv.putAll(Map.of("CHAT_REDIS_ENABLED", "true", "CHAT_REDIS_ENVIRONMENT", "auth-test",
                    "CHAT_REDIS_HOST", REDIS.getHost(), "CHAT_REDIS_PORT", REDIS.getMappedPort(6379).toString(),
                    "CHAT_REDIS_USERNAME", "chat-auth", "CHAT_REDIS_PASSWORD", REDIS_PASSWORD, "CHAT_REDIS_SSL",
                    Boolean.toString(PRODUCTION),
                    "CHAT_AUTH_REDIS_ENABLED", "true", "CORE_AUTH_PRODUCER_IDS", PRODUCER));
            Node subscribed = start("chat", fastEnv, trust);
            await(() -> status(polling, "/actuator/health/readiness") == 200
                    && status(subscribed, "/actuator/health/readiness") == 200);
            if (PRODUCTION) {
                productionChecks(jdbc, sessions, chatEnv, trust, polling, subscribed, regionId(jdbc));
                return;
            }
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
            if (MUSIC) {
                musicChecks(jdbc, polling, subscribed, first, second, revokedSid, region, room);
                if (Boolean.getBoolean("auth.integration.music-recovery")) {
                    musicRecoveryChecks(jdbc, chatEnv, trust, polling, subscribed, second, revokedSid, region, room);
                }
                return;
            }
            if (CONTROL) {
                String controlSid = UUID.randomUUID().toString();
                sessions.put(controlSid, session(controlSid));
                seedBrowserSession(jdbc, controlSid, UUID.randomUUID().toString());
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
            if (RUNTIME) {
                runtimeChecks(jdbc, sessions, chatEnv, trust, polling, subscribed, survivingSid, region, room);
                return;
            }
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
                    Map.of("X-CSRF-TOKEN", csrfToken, "Cookie", "MEO_AUTH_FLOW=" + flowCookie,
                            "X-Auth-Flow-Id", flowId, "X-Auth-Flow-Epoch", "1",
                            "Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, logout.statusCode(), logout.body());
            assertTrue(json.readTree(logout.body()).path("data").path("logoutReceipt").path("committed").asBoolean(),
                    logout.body());
            assertEquals("REVOKED", jdbc.queryForObject(
                    "SELECT status FROM auth_browser_sessions WHERE sid=?", String.class, revokedSid));
            await(() -> sessions.get(revokedSid).status() == HazelcastAuthStateStore.SessionStatus.REVOKED);
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
            assertEquals(200, status(polling, "/actuator/health/liveness"));
            assertTrue(polling.process().isAlive());
            relayMode.set(401);
            assertEquals(503, request(polling, "GET", "/api/v1/regions/" + region + "/chat-room", token(survivingSid),
                    null, Map.of()).statusCode());
            relayMode.set(0);
            assertEquals(200, request(polling, "GET", "/api/v1/regions/" + region + "/chat-room", token(survivingSid),
                    null, Map.of()).statusCode());
            await(() -> status(polling, "/actuator/health/readiness") == 200);
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

    private static int redisPort() {
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Redis fixture port unavailable");
        }
    }

    private long regionId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM regions WHERE code='41135'", Long.class);
    }

    private void proxyProduction(com.sun.net.httpserver.HttpExchange exchange, Node core) throws Exception {
        String path = exchange.getRequestURI().toString();
        if (path.startsWith("/internal/") && relayMode.get() != 0) {
            exchange.sendResponseHeaders(relayMode.get(), -1);
            return;
        }
        var request = HttpRequest.newBuilder(URI.create(core.base() + path))
                .timeout(java.time.Duration.ofSeconds(5)).header("X-Forwarded-Proto", "https");
        for (String header : List.of("Authorization", "Content-Type", "Origin", "Cookie", "X-CSRF-TOKEN",
                "X-Auth-Flow-Id", "X-Auth-Flow-Epoch", "X-Login-Operation-Id", "Idempotency-Key",
                "X-Auth-Login-Protocol", "X-Auth-Flow-Operation-Id", "X-Auth-Client-Expires-At")) {
            for (String value : exchange.getRequestHeaders().getOrDefault(header, List.of())) {
                request.header(header, value);
            }
        }
        byte[] body = exchange.getRequestBody().readNBytes(32769);
        var response = HttpClient.newHttpClient().send(request.method(exchange.getRequestMethod(),
                HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        for (String header : List.of("Set-Cookie", "Content-Type", "Cache-Control", "X-Auth-Flow-Id",
                "X-Auth-Flow-Epoch", "X-Auth-Flow-Revision")) {
            for (String value : response.headers().allValues(header)) {
                exchange.getResponseHeaders().add(header, value);
            }
        }
        exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
        exchange.getResponseBody().write(response.body());
    }

    private HttpResponse<String> browserRequest(HttpClient browser, String method, String path, String body,
            Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(productionCoreBase + path))
                .timeout(java.time.Duration.ofSeconds(10)).header("Origin", ORIGIN)
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        return browser.send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void prepareDirectAuthAuthority(JdbcTemplate jdbc) throws Exception {
        // Fresh isolated database: synthetic restore epoch and key pin, never an operational restore approval.
        byte[] key = java.util.Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        byte[] derived = javax.crypto.KDF.getInstance("HKDF-SHA256").deriveData(
                javax.crypto.spec.HKDFParameterSpec.ofExtract().addIKM(key)
                        .addSalt("meomuneum/auth-replay/v2/hkdf-salt".getBytes(StandardCharsets.UTF_8))
                        .thenExpand("meomuneum/auth-replay/v2/aes-256-gcm".getBytes(StandardCharsets.UTF_8), 32));
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update("meomuneum/auth-replay/v2/key-id".getBytes(StandardCharsets.UTF_8));
        String keyId = java.util.HexFormat.of().formatHex(digest.digest(derived));
        jdbc.update("INSERT INTO auth_replay_key_pin VALUES (1, ?)", keyId);
        jdbc.update("INSERT INTO auth_replay_iv_allocators VALUES (?, ?, FALSE)", keyId, new byte[12]);
        jdbc.update("""
                UPDATE auth_direct_projection_control SET cookie_mode='GENERATION_SCOPED',
                    readiness='PRODUCTION_READY', replay_key_id=?, restore_verified=TRUE WHERE singleton_id=1
                """, keyId);
    }

    private String login() throws Exception {
        var browser = HttpClient.newBuilder().sslContext(TLS.clientContext())
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var csrf = browserRequest(browser, "GET", "/api/v1/auth/token/csrf", null, Map.of());
        assertEquals(200, csrf.statusCode());
        String csrfToken = json.readTree(csrf.body()).path("data").path("csrf_token").asText();
        var bootstrap = browserRequest(browser, "POST", "/api/v1/auth/flows", null,
                Map.of("X-Auth-Login-Protocol", "direct-v2", "X-CSRF-TOKEN", csrfToken,
                        "X-Auth-Flow-Operation-Id", UUID.randomUUID().toString()));
        assertEquals(200, bootstrap.statusCode(), bootstrap.body());
        var flow = json.readTree(bootstrap.body()).path("data");
        String flowId = flow.path("flowId").asText();
        long epoch = flow.path("flowEpoch").asLong();
        assertFalse(flowId.isBlank(), "flow bootstrap must return an identity");
        var loginHeaders = Map.of("X-CSRF-TOKEN", csrfToken, "X-Auth-Flow-Id", flowId,
                "X-Auth-Flow-Epoch", Long.toString(epoch), "X-Login-Operation-Id", UUID.randomUUID().toString(),
                "X-Auth-Login-Protocol", "direct-v2", "X-Auth-Client-Expires-At",
                Long.toString(System.currentTimeMillis() + 60000));
        var loggedIn = browserRequest(browser, "POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", "auth-process@example.test",
                        "password", "synthetic-login-password")),
                loginHeaders);
        assertEquals(200, loggedIn.statusCode(), loggedIn.body());
        String access = json.readTree(loggedIn.body()).path("data").path("access_token").asText();
        String sid = new JwtTokenProvider(new JwtProperties("project-api", "project-api", SECRET, 3600, 1209600))
                .parseAccessToken(access).sessionId();
        var current = browserRequest(browser, "GET", "/api/v1/auth/flow", null,
                Map.of("X-Auth-Login-Protocol", "direct-v2"));
        assertEquals(200, current.statusCode());
        var activated = json.readTree(current.body()).path("data");
        assertTrue(activated.path("authenticated").asBoolean());
        issuedTokens.put(sid, access);
        browsers.put(sid, new BrowserSession(browser, activated.path("flowId").asText(),
                activated.path("flowEpoch").asLong(), csrfToken));
        var pair = new SessionPair(411001, sid);
        String validationBody = new String(InternalSessionProtocol.encodeRequest(List.of(pair)),
                StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean projected = false;
        do {
            var checked = browserRequest(browser, "POST", "/internal/v1/auth/sessions/validate", validationBody,
                    Map.of("Authorization", "Bearer " + SERVICE));
            assertEquals(200, checked.statusCode(), checked.body());
            projected = InternalSessionProtocol.response(checked.body().getBytes(StandardCharsets.UTF_8),
                    List.of(pair)).getFirst().active();
            if (!projected) {
                Thread.sleep(50);
            }
        } while (!projected && System.nanoTime() < deadline);
        assertTrue(projected, "direct login must reach the Core Chat session projection within five seconds");
        return sid;
    }

    private void productionChecks(JdbcTemplate jdbc,
            com.hazelcast.map.IMap<String, HazelcastAuthStateStore.AuthSession> sessions, Map<String, String> chatEnv,
            Path trust, Node firstTask, Node secondTask, long region) throws Exception {
        var trustedClient = HttpClient.newBuilder().sslContext(TLS.clientContext()).build();
        String wrongHost = productionCoreBase.replace(ProductionTlsFixture.CORE_HOST, "127.0.0.1");
        assertThrows(javax.net.ssl.SSLHandshakeException.class,
                () -> trustedClient.send(HttpRequest.newBuilder(URI.create(wrongHost + "/api/v1/auth/flow"))
                        .build(), HttpResponse.BodyHandlers.discarding()));
        var untrustedRedis = new com.muse.meomuneum.chat.redis.ChatRedisProperties("auth-test", REDIS.getHost(),
                REDIS.getMappedPort(6379), "chat-auth", REDIS_PASSWORD, true,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> new com.muse.meomuneum.chat.redis.ChatRedisConnection(untrustedRedis));
        String revoked = login();
        String survivor = login();
        long room = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE region_id=?", Long.class, region);
        Probe first = join(firstTask, revoked, region, room);
        Probe second = join(secondTask, revoked, region, room);
        Probe other = join(firstTask, survivor, region, room);
        other.sendMessage(room);
        long message = frameData(other.await("CHAT_ACK")).path("message").path("message_id").asLong();
        assertTrue(message > 0);
        for (Probe probe : List.of(first, second, other)) {
            assertEquals(message, frameData(probe.await("CHAT_MESSAGE")).path("message_id").asLong());
        }
        BrowserSession browser = browsers.get(revoked);
        var logout = browserRequest(browser.client(), "POST", "/api/v1/auth/logout", null,
                Map.of("X-Auth-Login-Protocol", "direct-v2", "X-CSRF-TOKEN", browser.csrf(),
                        "X-Auth-Flow-Id", browser.flowId(),
                        "X-Auth-Flow-Epoch", Long.toString(browser.epoch()), "Idempotency-Key",
                        UUID.randomUUID().toString()));
        assertEquals(200, logout.statusCode());
        assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
        assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
        assertFalse(other.closed.isDone());
        assertEquals(401, request(firstTask, "GET", "/api/v1/regions/" + region + "/chat-room",
                token(revoked), null, Map.of()).statusCode());
        relayMode.set(503);
        assertEquals(503, request(secondTask, "GET", "/api/v1/regions/" + region + "/chat-room",
                token(survivor), null, Map.of()).statusCode());
        assertEquals(1013, other.closed.get(5, TimeUnit.SECONDS));
        await(() -> status(firstTask, "/actuator/health/readiness") == 503);
        assertEquals(200, status(firstTask, "/actuator/health/liveness"));
        relayMode.set(0);
        await(() -> status(firstTask, "/actuator/health/readiness") == 200);
        runtimeChecks(jdbc, sessions, chatEnv, trust, firstTask, secondTask, survivor, region, room);
        Files.writeString(evidence.resolve("result.json"), json.writeValueAsString(Map.of(
                "profile", "prod", "chatTasks", 2, "coreHttps", true, "redisTls", true,
                "realLogin", true, "crossTaskMessage", true, "sidLogout", true,
                "dependencyFailures", true, "sigtermAndHistory", true, "offlineRecovery", true)));
    }

    private void recoveryCommand() throws Exception {
        var log = evidence.resolve("recovery-" + UUID.randomUUID() + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dloader.main=com.muse.meomuneum.chat.runtime.ChatRecoveryCommand", "-cp",
                System.getProperty("auth.integration.chat-jar"),
                "org.springframework.boot.loader.launch.PropertiesLauncher");
        builder.environment().clear();
        if (PRODUCTION) {
            builder.environment().put("CHAT_REDIS_SSL_CA_CERTIFICATE", TLS.certificate().toString());
        }
        builder.environment().putAll(Map.of("CHAT_DB_URL", MYSQL.getJdbcUrl(), "CHAT_DB_USERNAME", MYSQL.getUsername(),
                "CHAT_DB_PASSWORD", MYSQL.getPassword(), "CHAT_REDIS_ENVIRONMENT", "auth-test",
                "CHAT_REDIS_HOST", REDIS.getHost(), "CHAT_REDIS_PORT", REDIS.getMappedPort(6379).toString(),
                "CHAT_REDIS_USERNAME", "chat-auth", "CHAT_REDIS_PASSWORD", REDIS_PASSWORD,
                "CHAT_REDIS_SSL", Boolean.toString(PRODUCTION), "CHAT_RECOVERY_CONFIRM", "chat-stopped"));
        var command = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(command.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, command.exitValue(), "inspect recovery command log");
        } finally {
            command.destroyForcibly();
        }
    }

    private void runtimeChecks(JdbcTemplate jdbc,
            com.hazelcast.map.IMap<String, HazelcastAuthStateStore.AuthSession> sessions, Map<String, String> chatEnv,
            Path trust, Node polling, Node subscribed, String survivingSid, long region, long room) throws Exception {
        await(() -> {
            try {
                return request(polling, "GET", "/actuator/prometheus", METRICS, null, Map.of()).body()
                        .contains("chat_message_store_duration_seconds_count");
            } catch (Exception unavailable) {
                return false;
            }
        });
        assertEquals(401, request(polling, "GET", "/actuator/prometheus", null, null, Map.of()).statusCode());
        assertEquals(401,
                request(polling, "GET", "/actuator/prometheus", token(survivingSid), null, Map.of()).statusCode());
        assertEquals(200, request(polling, "GET", "/actuator/prometheus", METRICS, null, Map.of()).statusCode());
        Node terminating = start("chat", chatEnv, trust);
        String drainSid = PRODUCTION ? login() : UUID.randomUUID().toString();
        if (!PRODUCTION) {
            sessions.put(drainSid, session(drainSid));
            seedBrowserSession(jdbc, drainSid, UUID.randomUUID().toString());
        }
        Probe draining = join(terminating, drainSid, region, room);
        Probe other = join(subscribed, survivingSid, region, room);
        terminating.process().destroy();
        assertEquals(1013, draining.closed.get(25, TimeUnit.SECONDS));
        assertTrue(terminating.process().waitFor(30, TimeUnit.SECONDS));
        assertFalse(other.closed.isDone());
        assertEquals("OFFLINE", jdbc.queryForObject("SELECT state FROM chat_view_runtime WHERE view_id=?",
                String.class, draining.view));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? "
                + "AND ends_at=verified_at AND end_room_seq=verified_through_room_seq", Integer.class, draining.view));
        Probe resumed = connect(subscribed, drainSid, room, draining.membership, draining.view);
        assertEquals(200, request(polling, "GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                + draining.membership + "&mode=history", token(drainSid), null,
                Map.of("X-Chat-View-Session", resumed.view)).statusCode());
        var adminProperties = new com.muse.meomuneum.chat.redis.ChatRedisProperties("auth-test", REDIS.getHost(),
                REDIS.getMappedPort(6379), "fixture-admin", REDIS_PASSWORD, PRODUCTION,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1024,
                PRODUCTION ? TLS.certificate().toString() : "");
        var channels = new com.muse.meomuneum.chat.redis.ChatRedisChannels(adminProperties);
        try (var admin = new com.muse.meomuneum.chat.redis.ChatRedisConnection(adminProperties)) {
            String marker = admin.commands().sync().hget(channels.runtimeMarkerKey(), "generation");
            admin.commands().sync().del(channels.runtimeMarkerKey());
            assertEquals(1013, resumed.closed.get(5, TimeUnit.SECONDS));
            await(() -> status(subscribed, "/actuator/health/readiness") == 503);
            assertEquals(200, status(subscribed, "/actuator/health/liveness"));
            assertEquals(503, request(subscribed, "GET", "/api/v1/regions/" + region + "/chat-room",
                    token(drainSid), null, Map.of()).statusCode());
            admin.commands().sync().hset(channels.runtimeMarkerKey(), "generation", marker);
            Thread.sleep(1200);
            assertEquals(503, status(subscribed, "/actuator/health/readiness"));
            assertEquals(200, status(subscribed, "/actuator/health/liveness"));
            assertEquals(503, request(subscribed, "GET", "/api/v1/regions/" + region + "/chat-room",
                    token(drainSid), null, Map.of()).statusCode());
            assertTrue(subscribed.process().isAlive());
        }
        stop(polling);
        stop(subscribed);
        long oldEpoch = jdbc.queryForObject("SELECT epoch FROM chat_runtime_control WHERE environment='auth-test'",
                Long.class);
        recoveryCommand();
        assertEquals(oldEpoch + 1,
                jdbc.queryForObject("SELECT epoch FROM chat_runtime_control WHERE environment='auth-test'",
                        Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_receipts", Integer.class));
        Node fresh = start("chat", chatEnv, trust);
        Probe restored = connect(fresh, drainSid, room, draining.membership, draining.view);
        assertEquals(200, status(fresh, "/actuator/health/readiness"));
        try (var admin = new com.muse.meomuneum.chat.redis.ChatRedisConnection(adminProperties)) {
            String prior = admin.commands().sync().hget(channels.runtimeMarkerKey(), "generation");
            REDIS.getDockerClient().restartContainerCmd(REDIS.getContainerId()).exec();
            assertEquals(1013, restored.closed.get(5, TimeUnit.SECONDS));
            await(() -> status(fresh, "/actuator/health/readiness") == 503);
            await(() -> admin.isConnected());
            admin.commands().sync().hset(channels.runtimeMarkerKey(), "generation", prior);
            assertEquals(503, status(fresh, "/actuator/health/readiness"));
        }
        stop(fresh);
        recoveryCommand();
        Node finalNode = start("chat", chatEnv, trust);
        Probe finalView = connect(finalNode, drainSid, room, draining.membership, draining.view);
        var history = request(finalNode, "GET", "/api/v1/chat-rooms/" + room + "/messages?membership_id="
                + draining.membership + "&mode=history", token(drainSid), null,
                Map.of("X-Chat-View-Session", finalView.view));
        assertEquals(200, history.statusCode());
        assertEquals(1, json.readTree(history.body()).path("data").path("items").size());
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            Thread.sleep(1500);
            assertEquals(503,
                    request(finalNode, "GET", "/api/v1/regions/" + region + "/chat-room", token(drainSid), null,
                            Map.of()).statusCode());
            assertEquals(1013, finalView.closed.get(8, TimeUnit.SECONDS));
            assertEquals(200, status(finalNode, "/actuator/health/liveness"));
            assertEquals(503, status(finalNode, "/actuator/health/readiness"));
        } finally {
            MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec();
        }
        Files.writeString(evidence.resolve("runtime-lifecycle.json"), json.writeValueAsString(Map.of(
                "sigtermClose", 1013, "otherTaskPreserved", true, "lastVerifiedOnly", true, "metricsProtected", true,
                "markerLossClosed", true, "noAutomaticReopen", true, "offlineCommandEpoch", true,
                "redisRestartClosed", true, "historyAndReceiptPreserved", true, "dbPauseClosed", true)));
    }

    private Node start(String role, Map<String, String> additions, Path trust) throws Exception {
        var log = evidence.resolve(role + "-" + UUID.randomUUID() + ".log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString()));
        if (trust != null) {
            command.addAll(List.of("-Djavax.net.ssl.trustStore=" + trust,
                    "-Djavax.net.ssl.trustStorePassword=synthetic-fixture-password"));
        }
        if (PRODUCTION) {
            command.add("-Djdk.net.hosts.file=" + TLS.hosts());
        }
        command.addAll(
                List.of("-jar", System.getProperty("auth.integration." + role + "-jar"), "--server.address=127.0.0.1",
                        "--server.port=0", "--chat.message-cleanup.enabled=false"));
        if (PRODUCTION || role.equals("chat")) {
            command.add("--spring.profiles.active=" + (PRODUCTION ? "prod" : "chat-isolated"));
        }
        if (PRODUCTION && role.equals("core")) {
            command.addAll(List.of("--auth.direct-v2.safety-mode=PRODUCTION",
                    "--auth.direct-v2.backend-instances=1", "--auth.direct-v2.external-restore-epoch=1"));
        }
        var builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(Map.of(role.toUpperCase() + "_DB_URL", MYSQL.getJdbcUrl(),
                role.toUpperCase() + "_DB_USERNAME", MYSQL.getUsername(), role.toUpperCase() + "_DB_PASSWORD",
                MYSQL.getPassword(),
                "AUTH_JWT_SECRET", SECRET, "LOCATION_TOKEN_SECRET", LOCATION, "AUTH_CORS_ALLOWED_ORIGINS", ORIGIN,
                "AUTH_COOKIE_SECURE", Boolean.toString(PRODUCTION)));
        if (role.equals("core")) {
            builder.environment().putAll(Map.of("RECOMMENDATION_AI_BASE_URL", "http://127.0.0.1:1",
                    "SPEECH_TRANSCRIPTION_AI_BASE_URL", "http://127.0.0.1:1", "LOCATION_REVERSE_GEOCODING_BASE_URL",
                    "http://127.0.0.1:1"));
        }
        builder.environment().putAll(additions);
        if (PRODUCTION && role.equals("core")) {
            builder.environment().put("AUTH_DIRECT_V2_ENABLED", "true");
        }
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

    private void musicChecks(JdbcTemplate jdbc, Node firstTask, Node secondTask, Probe first, Probe second,
            String sid, long region, long room) throws Exception {
        jdbc.update("INSERT INTO music(provider,external_music_id,title,artist_name,youtube_video_id) "
                + "VALUES ('ITUNES','music-events-process','synthetic song','synthetic artist','abcdefghijk')");
        long music = jdbc.queryForObject("SELECT id FROM music WHERE external_music_id='music-events-process'",
                Long.class);
        jdbc.update("""
                INSERT INTO music_playback_sources(music_id,youtube_video_id,channel_id,channel_kind,review_reference,
                  reviewed_by,status,duration_millis,playback_country,verified_at,valid_until,version)
                VALUES (?,'abcdefghijk','synthetic','ARTIST','synthetic','test','VERIFIED',120000,'KR',
                  TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),1)
                """, music);
        jdbc.update("""
                INSERT INTO recommendation_sessions(user_id,region_id,trigger_type,input_type,conversation_key,status)
                VALUES (411001,?,'CHATBOT','TEXT','music-events-process','COMPLETED')
                """, region);
        long rec = jdbc.queryForObject("SELECT id FROM recommendation_sessions "
                + "WHERE conversation_key='music-events-process'", Long.class);
        jdbc.update("INSERT INTO recommendation_items(recommendation_session_id,music_id,rank_no) VALUES (?,?,1)", rec,
                music);
        String base = "/api/v1/chat-rooms/" + room;
        String firstQuery = "?membership_id=" + first.membership + "&view_session_id=" + first.view;
        String secondQuery = "?membership_id=" + second.membership + "&view_session_id=" + second.view;
        var added = request(secondTask, "POST", base + "/queue-items" + firstQuery, token(sid),
                json.writeValueAsString(Map.of("music_id", music)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, added.statusCode(), added.body());
        long current = json.readTree(added.body()).path("data").path("queue_item_id").asLong();
        for (Probe probe : List.of(first, second)) {
            awaitMusic(probe, "QUEUE_UPDATED", 1);
            assertEquals("PLAYING", awaitMusic(probe, "PLAYBACK_UPDATED", 1).path("playback").path("state").asText());
        }
        String pauseId = UUID.randomUUID().toString();
        String pauseBody = json.writeValueAsString(Map.of("action", "PAUSE", "queue_item_id", current,
                "expected_version", 1));
        var paused = request(firstTask, "POST", base + "/playback/commands" + secondQuery, token(sid), pauseBody,
                Map.of("Idempotency-Key", pauseId));
        assertEquals(200, paused.statusCode(), paused.body());
        for (Probe probe : List.of(first, second)) {
            assertEquals("PAUSED", awaitMusic(probe, "PLAYBACK_UPDATED", 2).path("playback").path("state").asText());
        }
        var retry = request(secondTask, "POST", base + "/playback/commands" + secondQuery, token(sid), pauseBody,
                Map.of("Idempotency-Key", pauseId));
        assertEquals(paused.body(), retry.body());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_command_receipts WHERE command_id=?",
                Integer.class, pauseId));
        var queued = request(firstTask, "POST", base + "/queue-items" + secondQuery, token(sid),
                json.writeValueAsString(Map.of("music_id", music)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, queued.statusCode(), queued.body());
        for (Probe probe : List.of(first, second)) {
            assertEquals("PAUSED", awaitMusic(probe, "QUEUE_UPDATED", 3).path("playback").path("state").asText());
        }
        var skipped = request(firstTask, "POST", base + "/playback/commands" + secondQuery, token(sid),
                json.writeValueAsString(Map.of("action", "SKIP", "queue_item_id", current, "expected_version", 3)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, skipped.statusCode(), skipped.body());
        for (Probe probe : List.of(first, second)) {
            var next = awaitMusic(probe, "PLAYBACK_UPDATED", 4);
            assertEquals("PLAYING", next.path("playback").path("state").asText());
            assertTrue(next.path("playback").path("current_queue_item_id").asLong() != current);
        }
        jdbc.update("""
                UPDATE playback_states SET state_changed_at=TIMESTAMPADD(SECOND,-121,UTC_TIMESTAMP(6)),
                  due_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE chat_room_id=?
                """, room);
        for (Probe probe : List.of(first, second)) {
            assertEquals("IDLE", awaitMusic(probe, "PLAYBACK_UPDATED", 5).path("playback").path("state").asText());
            assertEquals("IDLE", awaitMusic(probe, "PLAYBACK_SYNC", 5).path("playback").path("state").asText());
        }
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=? AND status='SENT'",
                Integer.class, room) == 8);
        assertEquals(5,
                jdbc.queryForObject("SELECT version FROM playback_states WHERE chat_room_id=?", Integer.class, room));
        var checks = Map.of("twoChatProcesses", true, "realCoreChecks", true, "crossTaskRest", true,
                "senderViaPubSub", true, "equalVersionPair", true, "pauseRetry", true, "pausedAdd", true,
                "skipAndServerEnd", true, "readOnlyPeriodicSync", true);
        Files.writeString(evidence.resolve("music-events.json"), json.writeValueAsString(checks));
        Files.writeString(evidence.resolve("music-event-examples.json"), json.writeValueAsString(musicExamples));
    }

    private void musicRecoveryChecks(JdbcTemplate jdbc, Map<String, String> chatEnv, Path trust,
            Node polling, Node subscribed, Probe view, String sid, long region, long room) throws Exception {
        long song = jdbc.queryForObject("SELECT id FROM music WHERE external_music_id='music-events-process'",
                Long.class);
        String base = "/api/v1/chat-rooms/" + room;
        String query = "?membership_id=" + view.membership + "&view_session_id=" + view.view;
        assertEquals(201, request(polling, "POST", base + "/queue-items" + query, token(sid),
                json.writeValueAsString(Map.of("music_id", song)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).statusCode());
        long current = jdbc.queryForObject("SELECT current_queue_item_id FROM playback_states WHERE chat_room_id=?",
                Long.class, room);
        String pauseId = UUID.randomUUID().toString();
        String pauseBody = json.writeValueAsString(Map.of("action", "PAUSE", "queue_item_id", current,
                "expected_version", 6));
        var paused = request(polling, "POST", base + "/playback/commands" + query, token(sid), pauseBody,
                Map.of("Idempotency-Key", pauseId));
        assertEquals(200, paused.statusCode(), paused.body());
        assertEquals(201, request(polling, "POST", base + "/queue-items" + query, token(sid),
                json.writeValueAsString(Map.of("music_id", song)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).statusCode());
        stop(subscribed);
        assertEquals(1013, view.closed.get(5, TimeUnit.SECONDS));
        Probe drained = connect(polling, sid, room, view.membership, view.view);
        var afterDrain = request(polling, "GET", base + "/queue-items" + query, token(sid), null, Map.of());
        assertEquals(200, afterDrain.statusCode(), afterDrain.body());
        assertEquals("PAUSED", json.readTree(afterDrain.body()).path("data").path("state").asText());
        polling.process().destroyForcibly();
        assertTrue(polling.process().waitFor(10, TimeUnit.SECONDS));
        // Deterministic expired-lease fault; the process has really been killed, no live owner is bypassed.
        jdbc.update("UPDATE chat_runtime_tasks SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE environment='auth-test' AND state='ACTIVE'");
        Node replacement = start("chat", chatEnv, trust);
        Probe resumed = connect(replacement, sid, room, drained.membership, drained.view);
        var recovered = request(replacement, "GET", base + "/queue-items" + query, token(sid), null, Map.of());
        assertEquals(200, recovered.statusCode(), recovered.body());
        var data = json.readTree(recovered.body()).path("data");
        assertEquals("PAUSED", data.path("state").asText());
        assertEquals(8, data.path("version").asLong());
        assertEquals("abcdefghijk", data.path("source_video_id").asText());
        assertEquals(current, data.path("current_queue_item_id").asLong());
        long epoch = data.path("epoch").asLong();
        assertTrue(epoch > 0);
        REDIS.getDockerClient().restartContainerCmd(REDIS.getContainerId()).exec();
        assertEquals(1013, resumed.closed.get(8, TimeUnit.SECONDS));
        await(() -> status(replacement, "/actuator/health/readiness") == 503);
        assertEquals(503, request(replacement, "GET", base + "/queue-items" + query, token(sid), null,
                Map.of()).statusCode());
        stop(replacement);
        // Simulate an outage beyond the original verified grace, without manufacturing a new interval.
        jdbc.update("""
                UPDATE chat_view_intervals i JOIN chat_view_runtime v ON v.view_id=i.view_session_id
                SET i.started_at=TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(6)),
                  i.verified_at=TIMESTAMPADD(SECOND,-31,UTC_TIMESTAMP(6)),
                  i.ends_at=CASE WHEN i.ends_at IS NULL THEN NULL ELSE
                    TIMESTAMPADD(SECOND,-31,UTC_TIMESTAMP(6)) END
                WHERE v.environment='auth-test' AND v.room_id=?
                """, room);
        jdbc.update("""
                UPDATE chat_music_outbox SET status='PENDING',sent_at=NULL,claim_token=?,
                  claim_until=TIMESTAMPADD(SECOND,10,UTC_TIMESTAMP(6))
                WHERE room_id=? AND event_type='QUEUE_UPDATED' AND music_version=8
                """, UUID.randomUUID().toString(), room);
        long receipts = jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_command_receipts WHERE room_id=?",
                Long.class, room);
        recoveryCommand();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE environment='auth-test' "
                + "AND epoch=? AND status='PENDING'", Integer.class, epoch));
        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_command_receipts WHERE room_id=?",
                Long.class, room));
        Node fresh = start("chat", chatEnv, trust);
        Probe restored = connect(fresh, sid, room, resumed.membership, resumed.view);
        var latest = request(fresh, "GET", base + "/queue-items" + query, token(sid), null, Map.of());
        assertEquals(200, latest.statusCode(), latest.body());
        var finalState = json.readTree(latest.body()).path("data");
        assertEquals(epoch + 1, finalState.path("epoch").asLong());
        assertEquals("IDLE", finalState.path("state").asText());
        assertEquals(0, finalState.path("items").size());
        assertEquals("CANCELED", jdbc.queryForObject("SELECT status FROM music_queue_items WHERE id=?",
                String.class, current));
        var replay = request(fresh, "POST", base + "/playback/commands" + query, token(sid), pauseBody,
                Map.of("Idempotency-Key", pauseId));
        assertEquals(paused.body(), replay.body());
        assertEquals(finalState.path("version").asLong(), jdbc.queryForObject(
                "SELECT version FROM playback_states WHERE chat_room_id=?", Long.class, room));
        assertFalse(restored.closed.isDone());
        Files.writeString(evidence.resolve("music-recovery.json"), json.writeValueAsString(Map.of(
                "sigtermPausedPreserved", true, "realSigkill", true, "sameViewTaskTakeover", true,
                "leaseExpiryFault", true, "realRedisRestartClosed", true, "operatorCommandNewEpoch", true,
                "oldOutboxExpired", true, "expiredAbsenceCleared", true, "receiptReplayDidNotRestoreState", true)));
    }

    private final List<com.fasterxml.jackson.databind.JsonNode> musicExamples = new ArrayList<>();

    private com.fasterxml.jackson.databind.JsonNode awaitMusic(Probe probe, String type, long version)
            throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            var data = frameData(probe.await(type));
            if (data.path("version").asLong() >= version) {
                musicExamples.add(json.valueToTree(Map.of("type", type, "data", data)));
                return data;
            }
        }
        throw new AssertionError("latest music snapshot missing");
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
        return location(sid, region, 411001L);
    }

    private String location(String sid, long region, long userId) {
        var provider = new com.muse.meomuneum.location.security.LocationResolutionTokenProvider(
                new com.muse.meomuneum.location.config.LocationTokenProperties(LOCATION), java.time.Clock.systemUTC());
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
        return provider.issueForSession(userId, sid, parent, local, null).value();
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

    private String seedBrowserSession(JdbcTemplate jdbc, String sid, String cookie) throws Exception {
        return seedBrowserSession(jdbc, sid, cookie, 411001L);
    }

    private String seedBrowserSession(JdbcTemplate jdbc, String sid, String cookie, long userId) throws Exception {
        String flowId = UUID.randomUUID().toString();
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String cookieHash = java.util.HexFormat.of().formatHex(
                mac.doFinal(("auth-flow-cookie:" + cookie).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO auth_browser_flows
                    (flow_id,flow_cookie_hash,flow_epoch,revision,lifecycle,active_session_sid,
                     cookie_expires_at,created_at,updated_at)
                VALUES (?,?,1,1,'AUTHENTICATED',?,DATE_ADD(NOW(6),INTERVAL 1 DAY),NOW(6),NOW(6))
                """, flowId, cookieHash, sid);
        jdbc.update("""
                INSERT INTO auth_browser_sessions
                    (sid,flow_id,flow_epoch,user_id,status,idle_expires_at,absolute_expires_at,created_at,updated_at)
                VALUES (?,?,1,?,'ACTIVE',DATE_ADD(NOW(6),INTERVAL 1 DAY),
                        DATE_ADD(NOW(6),INTERVAL 1 DAY),NOW(6),NOW(6))
                """, sid, flowId, userId);
        return flowId;
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
        return token(sid, 411001L);
    }

    private String token(String sid, long userId) {
        if (PRODUCTION) {
            return java.util.Objects.requireNonNull(issuedTokens.get(sid), "real login token required");
        }
        User user = new User() {
        };
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        return new JwtTokenProvider(new JwtProperties("project-api", "project-api", SECRET, 3600, 1209600))
                .createAccessToken(user, sid);
    }

    private HazelcastAuthStateStore.AuthSession session(String sid) {
        return session(sid, 411001L);
    }

    private HazelcastAuthStateStore.AuthSession session(String sid, long userId) {
        Instant now = Instant.now();
        return new HazelcastAuthStateStore.AuthSession(userId, sid, "fixture",
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
