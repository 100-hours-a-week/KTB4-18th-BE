package com.muse.meomuneum.chat.view;

import static com.muse.meomuneum.chat.support.ChatTestSessions.sid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.message.service.ChatMessageStorageService;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.user.repository.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.message-cleanup.enabled=false", "recommendation.ai.base-url=http://localhost:8000",
        "speech.transcription.ai.base-url=http://localhost:8000",
        "location.reverse-geocoding.base-url=http://localhost:8000"})
@ActiveProfiles("test")
@Import(ChatViewStorageIntegrationTest.TimeConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ChatViewStorageIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-13T00:00:00.123456Z");
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_view_test").withUsername("test").withPassword("test");

    @Autowired
    private ChatViewStorageService storage;
    @Autowired
    private ChatViewLifecycle lifecycle;
    @Autowired
    private ChatPresenceRegistry presence;
    @Autowired
    private ChatRoomEntryService entry;
    @Autowired
    private ChatMessageStorageService messages;
    @Autowired
    private ChatRoomMemberRepository members;
    @Autowired
    private UserRepository users;
    @Autowired
    private ChatBanStorageService bans;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private com.muse.meomuneum.chat.message.query.ChatMessageQueryService queries;
    @Autowired
    private com.muse.meomuneum.global.security.JwtTokenProvider jwt;
    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;
    private Long userId;
    private Long roomId;
    private Long membershipId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void reset() {
        jdbc.queryForList("SELECT id FROM users WHERE email LIKE 'view-%'", Long.class).forEach(entry::leaveAll);
        jdbc.update("DELETE FROM chat_view_sessions");
        jdbc.update("DELETE FROM chat_room_messages");
        jdbc.update("DELETE FROM chat_room_members");
        jdbc.update("DELETE FROM chat_bans");
        jdbc.update("DELETE FROM users WHERE email LIKE 'view-%'");
        clock.set(START);
        jdbc.update("""
                INSERT INTO users (email,password_hash,nickname,role)
                VALUES ('view-owner@example.com','test-only','view-owner','USER')
                """);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'view-owner@example.com'", Long.class);
        roomId = jdbc.queryForObject("SELECT id FROM chat_rooms ORDER BY id LIMIT 1", Long.class);
        membershipId = createMembership();
    }

    @Test
    void beginsAtActualSubscriptionAndUsesIdsToDisambiguateSameMicrosecond() {
        Long before = message("before");
        entry.connect(userId, sid(userId), roomId, membershipId, "socket");
        lifecycle.connected(userId, roomId, membershipId, "socket", null);
        assertEquals(0, count("chat_view_sessions"));
        assertThrows(ChatRoomException.class, () -> lifecycle.authorize(userId, roomId, membershipId, null));
        String view = lifecycle.subscribed("socket", "room", topic());
        Long after = message("after");
        List<ChatViewInterval> ranges = lifecycle.authorize(userId, roomId, membershipId, view);
        assertFalse(permits(ranges, before, START));
        assertTrue(permits(ranges, after, START));
        assertEquals(local(START), ranges.getFirst().startedAt());
    }

    @Test
    void reconnectWithinThirtySecondsKeepsTheSameIntervalAndDoesNotExtendRepeatedDisconnect() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        clock.set(START.plusSeconds(5));
        storage.disconnected(view);
        clock.set(START.plusSeconds(15));
        storage.disconnected(view);
        assertEquals(local(START.plusSeconds(35)), jdbc.queryForObject(
                "SELECT ends_at FROM chat_view_intervals", LocalDateTime.class));
        Long duringGrace = message("grace");
        clock.set(START.plusSeconds(34));
        assertEquals(view, storage.subscribed(userId, roomId, membershipId, view));
        assertEquals(1, count("chat_view_intervals"));
        assertTrue(permits(storage.authorizedIntervals(userId, roomId, membershipId, view),
                duringGrace, START.plusSeconds(15)));
    }

    @Test
    void exactlyThirtySecondsEndsGraceAndLongDisconnectNeverGrantsTheAbsentGap() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        storage.disconnected(view);
        clock.set(START.plusSeconds(29));
        Long grace = message("grace");
        clock.set(START.plusSeconds(30));
        Long boundary = message("boundary");
        clock.set(START.plusSeconds(120));
        Long absent = message("absent");
        storage.subscribed(userId, roomId, membershipId, view);
        Long live = message("live");
        List<ChatViewInterval> ranges = storage.authorizedIntervals(userId, roomId, membershipId, view);
        assertEquals(2, ranges.size());
        assertTrue(permits(ranges, grace, START.plusSeconds(29)));
        assertFalse(permits(ranges, boundary, START.plusSeconds(30)));
        assertFalse(permits(ranges, absent, START.plusSeconds(120)));
        assertTrue(permits(ranges, live, START.plusSeconds(120)));
    }

    @Test
    void aDisconnectedTabHasItsOwnGraceWhileAnotherSocketKeepsTheUserSeatAlive() {
        String first = connectAndSubscribe("first", null);
        String second = connectAndSubscribe("second", null);
        assertNotEquals(first, second);
        presence.disconnect("first");
        clock.set(START.plusSeconds(31));
        Long absentForFirst = message("only-second");
        assertEquals(1, presence.connectedCount(roomId));
        assertThrows(ChatRoomException.class, () -> lifecycle.authorize(userId, roomId, membershipId, first));
        connectAndSubscribe("first-again", first);
        assertFalse(permits(lifecycle.authorize(userId, roomId, membershipId, first),
                absentForFirst, START.plusSeconds(31)));
        assertTrue(permits(lifecycle.authorize(userId, roomId, membershipId, second),
                absentForFirst, START.plusSeconds(31)));
    }

    @Test
    void explicitLeaveEndsAllViewsAndOldMembershipCannotResumeThem() {
        String first = connectAndSubscribe("first", null);
        String second = connectAndSubscribe("second", null);
        presence.disconnect("first");
        clock.set(START.plusSeconds(5));
        entry.leave(userId, sid(userId), roomId, membershipId);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_sessions WHERE ended_at IS NOT NULL",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_intervals WHERE ends_at IS NULL",
                Integer.class));
        assertThrows(ChatRoomException.class, () -> lifecycle.authorize(userId, roomId, membershipId, second));
        membershipId = createMembership();
        assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, roomId, membershipId, first));
    }

    @Test
    void earlierTransactionSnapshotCannotRestoreAnEndedMembership() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(1, count("chat_view_sessions"));
            assertTrue(members.findByIdAndUser_IdAndChatRoom_Id(membershipId, userId, roomId)
                    .orElseThrow().isActive());
            entry.leave(userId, sid(userId), roomId, membershipId);
            assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, roomId, membershipId, view));
            status.setRollbackOnly();
        });
    }

    @Test
    void anotherValidMemberCannotUseTheOwnersView() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        jdbc.update("""
                INSERT INTO users (email,password_hash,nickname,role)
                VALUES ('view-other@example.com','test-only','view-other','USER')
                """);
        Long otherUser = jdbc.queryForObject("SELECT id FROM users WHERE email = 'view-other@example.com'", Long.class);
        jdbc.update("INSERT INTO chat_room_members (user_id,room_id,created_at) VALUES (?,?,?)",
                otherUser, roomId, local(START));
        Long otherMembership = jdbc.queryForObject("SELECT id FROM chat_room_members WHERE user_id = ?",
                Long.class, otherUser);
        assertThrows(ChatRoomException.class, () -> storage.validateContext(otherUser, roomId, otherMembership, view));
    }

    @Test
    void rejectsMissingMalformedWrongOwnerRoomMembershipAndBannedContexts() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        assertThrows(ChatRoomException.class,
                () -> storage.validateContext(userId, roomId, membershipId, UUID.randomUUID().toString()));
        assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, roomId, membershipId, "bad"));
        assertThrows(ChatRoomException.class, () -> storage.validateContext(-1L, roomId, membershipId, view));
        assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, -1L, membershipId, view));
        assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, roomId, -1L, view));
        jdbc.update("""
                INSERT INTO chat_bans (user_id,banned_by_user_id,reason,created_at,expires_at)
                VALUES (?,?,'PROFANITY',?,?)
                """, userId, userId, local(START), local(START.plusSeconds(3600)));
        assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, roomId, membershipId, view));
    }

    @Test
    void restartClosesUnprovenOpenRangeAtLastCheckpointAndKeepsNewProcessSeparate() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        Long verified = message("verified");
        storage.checkpoint(view, roomId);
        clock.set(START.plusSeconds(2));
        Long unverified = message("unverified");
        ChatViewStorageService restarted = restartedStorage();
        clock.set(START.plusSeconds(100));
        Long duringRestart = message("restart-gap");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        List<ChatViewInterval> ranges = tx.execute(status -> {
            restarted.subscribed(userId, roomId, membershipId, view);
            return restarted.authorizedIntervals(userId, roomId, membershipId, view);
        });
        assertEquals(2, ranges.size());
        assertTrue(permits(ranges, verified, START));
        assertFalse(permits(ranges, unverified, START.plusSeconds(2)));
        assertFalse(permits(ranges, duringRestart, START.plusSeconds(100)));
        assertEquals("RESTART", jdbc.queryForObject(
                "SELECT end_reason FROM chat_view_intervals ORDER BY id LIMIT 1", String.class));
    }

    @Test
    void restartPreservesRecordedDisconnectDeadlineAndMissingRecordFailsClosed() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        storage.disconnected(view);
        ChatViewStorageService restarted = restartedStorage();
        assertEquals(local(START.plusSeconds(30)), jdbc.queryForObject(
                "SELECT ends_at FROM chat_view_intervals", LocalDateTime.class));
        jdbc.update("DELETE FROM chat_view_sessions WHERE id = ?", view);
        assertEquals(0, count("chat_view_intervals"));
        assertThrows(ChatRoomException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            restarted.subscribed(userId, roomId, membershipId, view);
            return null;
        }));
    }

    @Test
    void unsubscribeHasNoGraceAndResubscribeExcludesTheGap() {
        String view = connectAndSubscribe("first", null);
        lifecycle.unsubscribed("first", "room");
        Long absent = message("unsubscribed");
        lifecycle.subscribed("first", "room-again", topic());
        Long live = message("live");
        List<ChatViewInterval> ranges = lifecycle.authorize(userId, roomId, membershipId, view);
        assertFalse(permits(ranges, absent, START));
        assertTrue(permits(ranges, live, START));
    }

    @Test
    void reconnectAtExactGraceDeadlineStartsANewInterval() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        storage.disconnected(view);
        clock.set(START.plusSeconds(30));
        storage.subscribed(userId, roomId, membershipId, view);
        assertEquals(2, count("chat_view_intervals"));
    }

    @Test
    void unrecordedDisconnectCannotReopenAnUnboundedIntervalInTheSameProcess() {
        String view = storage.subscribed(userId, roomId, membershipId, null);
        Long proven = message("proven");
        storage.checkpoint(view, roomId);
        clock.set(START.plusSeconds(60));
        Long unproven = message("unproven-gap");
        storage.subscribed(userId, roomId, membershipId, view);
        List<ChatViewInterval> ranges = storage.authorizedIntervals(userId, roomId, membershipId, view);
        assertTrue(permits(ranges, proven, START));
        assertFalse(permits(ranges, unproven, START.plusSeconds(60)));
        assertEquals("UNVERIFIED", jdbc.queryForObject(
                "SELECT end_reason FROM chat_view_intervals ORDER BY id LIMIT 1", String.class));
    }

    @Test
    void expirationAndCleanupCannotEraseAnActiveViewOrMoveItsBoundaryBackwards() {
        Long before = message("old");
        String view = storage.subscribed(userId, roomId, membershipId, null);
        clock.set(START.plusSeconds(24 * 3600));
        messages.deleteExpiredMessages();
        storage.checkpoint(view, roomId);
        assertEquals(before, jdbc.queryForObject(
                "SELECT verified_through_message_id FROM chat_view_intervals", Long.class));
        storage.deleteExpiredRecords();
        assertEquals(1, count("chat_view_sessions"));
        storage.disconnected(view);
        clock.set(START.plusSeconds(48 * 3600 + 30));
        assertThrows(ChatRoomException.class, () -> storage.validateContext(userId, roomId, membershipId, view));
        storage.deleteExpiredRecords();
        assertEquals(0, count("chat_view_sessions"));
        assertEquals(0, count("chat_view_intervals"));
    }

    @Test
    void recoveryPagesStayWithinSnapshotWhileLiveMessagesContinue() {
        String view = connectAndSubscribe("query", null);
        Long first = message("first");
        Long second = message("second");
        Long third = message("third");
        String cursor = (String) queries.ready("query", view, 0).get("recovery_cursor");
        var page = queries.query(userId, roomId, membershipId, view, "recovery", cursor, 2);
        assertEquals(List.of(first, second), page.items().stream().map(item -> item.messageId()).toList());
        assertTrue(page.hasNext());
        Long live = message("live-after-snapshot");
        var next = queries.query(userId, roomId, membershipId, view, "recovery", page.nextCursor(), 2);
        assertEquals(List.of(third), next.items().stream().map(item -> item.messageId()).toList());
        assertFalse(next.hasNext());
        assertEquals(third.longValue(), next.snapshotMessageId());
        var history = queries.query(userId, roomId, membershipId, view, "history", null, 2);
        assertEquals(List.of(live, third), history.items().stream().map(item -> item.messageId()).toList());
        var older = queries.query(userId, roomId, membershipId, view, "history", history.nextCursor(), 2);
        assertEquals(List.of(second, first), older.items().stream().map(item -> item.messageId()).toList());
    }

    @Test
    void pagesAndClientIdLookupExcludeBeforeSubscribeAndAbsentMessages() {
        Long before = message("before-subscribe");
        String view = connectAndSubscribe("query", null);
        Long live = message("live");
        presence.disconnect("query");
        clock.set(START.plusSeconds(29));
        Long grace = message("grace");
        clock.set(START.plusSeconds(30));
        Long absent = message("absent");
        clock.set(START.plusSeconds(120));
        connectAndSubscribe("query-again", view);
        Long resumed = message("resumed");
        var page = queries.query(userId, roomId, membershipId, view, "recovery", null, 50);
        assertEquals(List.of(live, grace, resumed), page.items().stream().map(item -> item.messageId()).toList());
        assertFalse(page.hasNext());
        assertEquals("ABSENCE", page.gaps().getFirst().reason());
        for (Long forbidden : List.of(before, absent)) {
            String client = jdbc.queryForObject("SELECT client_message_id FROM chat_room_messages WHERE id = ?",
                    String.class, forbidden);
            assertThrows(com.muse.meomuneum.chat.message.query.ChatQueryException.class,
                    () -> queries.byClientId(userId, roomId, membershipId, view, client));
        }
        String client = jdbc.queryForObject("SELECT client_message_id FROM chat_room_messages WHERE id = ?",
                String.class, resumed);
        assertEquals(resumed, queries.byClientId(userId, roomId, membershipId, view, client).messageId());
    }

    @Test
    void cursorCannotChangeViewModeOrPayloadAndEveryPageRechecksDeparture() {
        String first = connectAndSubscribe("first", null);
        String second = connectAndSubscribe("second", null);
        message("first");
        message("second");
        String cursor = (String) queries.ready("first", first, 0).get("recovery_cursor");
        assertThrows(com.muse.meomuneum.chat.message.query.ChatQueryException.class,
                () -> queries.query(userId, roomId, membershipId, second, "recovery", cursor, 1));
        assertThrows(com.muse.meomuneum.chat.message.query.ChatQueryException.class,
                () -> queries.query(userId, roomId, membershipId, first, "history", cursor, 1));
        assertThrows(com.muse.meomuneum.chat.message.query.ChatQueryException.class,
                () -> queries.query(userId, roomId, membershipId, first, "recovery", "x" + cursor, 1));
        var page = queries.query(userId, roomId, membershipId, first, "recovery", cursor, 1);
        entry.leave(userId, sid(userId), roomId, membershipId);
        assertThrows(ChatRoomException.class,
                () -> queries.query(userId, roomId, membershipId, first, "recovery", page.nextCursor(), 1));
    }

    @Test
    void clientIdLookupDoesNotReturnAnotherAuthorsMessageAndExpiredCursorCannotReviveData() {
        String view = connectAndSubscribe("query", null);
        jdbc.update(
                "INSERT INTO users (email,password_hash,nickname,role) " +
                        "VALUES ('view-writer@example.com','test-only','view-writer','USER')");
        Long writer = jdbc.queryForObject("SELECT id FROM users WHERE email = 'view-writer@example.com'", Long.class);
        String foreignId = UUID.randomUUID().toString();
        messages.store(writer, roomId, foreignId, "other-author");
        assertThrows(com.muse.meomuneum.chat.message.query.ChatQueryException.class,
                () -> queries.byClientId(userId, roomId, membershipId, view, foreignId));
        message("expires");
        clock.set(START.plusSeconds(1));
        String cursor = (String) queries.ready("query", view, 0).get("recovery_cursor");
        clock.set(START.plusSeconds(24 * 3600));
        var page = queries.query(userId, roomId, membershipId, view, "recovery", cursor, 50);
        assertTrue(page.items().isEmpty());
        assertEquals("RETENTION", page.gaps().getFirst().reason());
        clock.set(START.plusSeconds(24 * 3600 + 1));
        assertThrows(com.muse.meomuneum.chat.message.query.ChatQueryException.class,
                () -> queries.query(userId, roomId, membershipId, view, "recovery", cursor, 50));
    }

    @Test
    void activeBanAndSocketDisconnectBlockQueriesEvenWithSavedCursor() {
        String view = connectAndSubscribe("query", null);
        message("first");
        String cursor = (String) queries.ready("query", view, 0).get("recovery_cursor");
        jdbc.update(
                "INSERT INTO chat_bans (user_id,banned_by_user_id,reason,created_at,expires_at) " +
                        "VALUES (?,?,'PROFANITY',?,?)",
                userId, userId, local(START), local(START.plusSeconds(3600)));
        assertThrows(ChatRoomException.class,
                () -> queries.query(userId, roomId, membershipId, view, "recovery", cursor, 50));
        jdbc.update("DELETE FROM chat_bans");
        presence.disconnect("query");
        assertThrows(ChatRoomException.class,
                () -> queries.query(userId, roomId, membershipId, view, "recovery", cursor, 50));
    }

    @Test
    void realHttpRequiresAuthenticationViewHeaderAndValidBoundCursor() throws Exception {
        String view = connectAndSubscribe("query", null);
        Long id = message("http");
        String path = "/api/v1/chat-rooms/" + roomId + "/messages?membership_id=" + membershipId;
        assertEquals(401, http(path, null, view).statusCode());
        String token = jwt.createAccessToken(users.findById(userId).orElseThrow(),
                sid(userId));
        assertEquals(400, http(path, token, null).statusCode());
        assertEquals(400, http(path + "&size=51", token, view).statusCode());
        var response = http(path, token, view);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"message_id\":" + id));
        assertEquals(403, http(path, token, UUID.randomUUID().toString()).statusCode());
        assertEquals(400, http(path + "&cursor=invalid", token, view).statusCode());
        entry.leave(userId, sid(userId), roomId, membershipId);
        assertEquals(403, http(path, token, view).statusCode());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_RECOVERY_FE_DIR", matches = ".+")
    void actualFrontendClientRecoversAndMergesThroughRealHttpAndWebSocket() throws Exception {
        clock.useRealTime();
        var directory = java.nio.file.Path.of(System.getenv("CHAT_RECOVERY_FE_DIR"));
        var process = new ProcessBuilder("node", "node_modules/vitest/vitest.mjs", "run",
                "src/features/chat-entry/services/chatRecovery.integration.test.ts")
                .directory(directory.toFile()).redirectErrorStream(true);
        process.environment().put("CHAT_RECOVERY_INTEGRATION_URL", "http://127.0.0.1:" + port);
        process.environment().put("CHAT_RECOVERY_INTEGRATION_TOKEN",
                jwt.createAccessToken(users.findById(userId).orElseThrow(), sid(userId)));
        process.environment().put("CHAT_RECOVERY_INTEGRATION_ROOM", roomId.toString());
        process.environment().put("CHAT_RECOVERY_INTEGRATION_MEMBER", membershipId.toString());
        Process running = process.start();
        if (!running.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
            running.destroyForcibly();
            throw new AssertionError("frontend integration timed out");
        }
        String output = new String(running.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, running.exitValue(), output);
    }

    private java.net.http.HttpResponse<String> http(String path, String token, String view) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (view != null) {
            request.header("X-Chat-View-Session", view);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private ChatViewStorageService restartedStorage() {
        ChatViewStorageService restarted = new ChatViewStorageService(jdbc, members, users, bans, clock);
        restarted.recoverAfterRestart();
        return restarted;
    }

    private String connectAndSubscribe(String socket, String view) {
        entry.connect(userId, sid(userId), roomId, membershipId, socket);
        lifecycle.connected(userId, roomId, membershipId, socket, view);
        return lifecycle.subscribed(socket, "room", topic());
    }

    private String topic() {
        return "/topic/chat-rooms/" + roomId;
    }

    private Long createMembership() {
        jdbc.update("INSERT INTO chat_room_members (user_id,room_id,created_at,auth_session_id) VALUES (?,?,?,?)",
                userId, roomId, local(clock.instant()), sid(userId));
        return jdbc.queryForObject("SELECT id FROM chat_room_members WHERE user_id = ? AND deleted_at IS NULL",
                Long.class, userId);
    }

    private Long message(String content) {
        return messages.store(userId, roomId, UUID.randomUUID().toString(), content).message().getId();
    }

    private boolean permits(List<ChatViewInterval> ranges, long id, Instant time) {
        return ranges.stream().anyMatch(range -> range.permits(id, local(time)));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static LocalDateTime local(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock viewTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = START;
        private volatile boolean realTime;

        void set(Instant time) {
            realTime = false;
            instant = time;
        }

        void useRealTime() {
            realTime = true;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return realTime ? Instant.now() : instant;
        }
    }
}
