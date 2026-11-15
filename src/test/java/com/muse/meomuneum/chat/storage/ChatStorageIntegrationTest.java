package com.muse.meomuneum.chat.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.ban.domain.ChatBan;
import com.muse.meomuneum.chat.ban.domain.ChatBanReason;
import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.message.service.ChatMessageSaveResult;
import com.muse.meomuneum.chat.message.service.ChatMessageStorageService;

@SpringBootTest(properties = {"chat.message-cleanup.enabled=false", "recommendation.ai.base-url=http://localhost:8000",
        "speech.transcription.ai.base-url=http://localhost:8000",
        "location.reverse-geocoding.base-url=http://localhost:8000"})
@ActiveProfiles("test")
@Import(ChatStorageIntegrationTest.TimeConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ChatStorageIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00.123456Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_storage_test").withUsername("test").withPassword("test");

    @Autowired
    private ChatMessageStorageService messages;

    @Autowired
    private ChatBanStorageService bans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long userId;
    private Long roomId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM chat_room_messages");
        jdbc.update("DELETE FROM chat_bans");
        jdbc.update("DELETE FROM users WHERE email LIKE 'storage-%'");
        clock.set(START);
        userId = createUser("first");
        roomId = jdbc.queryForObject("SELECT id FROM chat_rooms ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void migratesAndStoresNormalMessageWithMicrosecondPrecision() {
        ChatMessageSaveResult saved = messages.store(userId, roomId, "client-1", "안녕하세요 🎵");

        assertTrue(saved.created());
        assertTrue(saved.message().getId() > 0);
        assertEquals(userId, saved.message().getUser().getId());
        assertEquals(roomId, saved.message().getChatRoom().getId());
        assertEquals("안녕하세요 🎵", saved.message().getContent());
        assertEquals(LocalDateTime.ofInstant(START, ZoneOffset.UTC),
                messages.findUnexpiredById(saved.message().getId()).orElseThrow().getCreatedAt());
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE description IN ('create chat room messages table', 'create chat bans table') AND success = TRUE
                """, Integer.class));
    }

    @Test
    void concurrentRetriesStoreExactlyOneMessageAndReturnSameId() throws Exception {
        List<ChatMessageSaveResult> results = concurrently(
                () -> messages.store(userId, roomId, "retry", "같은 메시지"),
                () -> messages.store(userId, roomId, "retry", "같은 메시지"));

        assertEquals(results.get(0).message().getId(), results.get(1).message().getId());
        assertEquals(1L, results.stream().filter(ChatMessageSaveResult::created).count());
        assertEquals(1, count("chat_room_messages"));
    }

    @Test
    void retryAfterAnEarlierTransactionSnapshotReadsTheCommittedMessage() throws Exception {
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch stored = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ChatMessageSaveResult> retry = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        assertEquals(0, count("chat_room_messages"));
                        snapshotReady.countDown();
                        await(stored);
                        return messages.store(userId, roomId, "snapshot", "재시도");
                    }));
            try {
                assertTrue(snapshotReady.await(10, TimeUnit.SECONDS));
                ChatMessageSaveResult original = messages.store(userId, roomId, "snapshot", "재시도");
                stored.countDown();
                ChatMessageSaveResult result = retry.get(10, TimeUnit.SECONDS);
                assertFalse(result.created());
                assertEquals(original.message().getId(), result.message().getId());
            } finally {
                stored.countDown();
            }
        }
    }

    @Test
    void clientIdsAreScopedToUserAndComparedCaseSensitively() {
        Long secondUser = createUser("second");
        messages.store(userId, roomId, "Client", "첫 메시지");
        messages.store(userId, roomId, "client", "둘째 메시지");
        messages.store(secondUser, roomId, "Client", "다른 사용자");

        assertEquals(3, count("chat_room_messages"));
    }

    @Test
    void rejectsReuseForDifferentContentOrRoomWithoutChangingOriginal() {
        ChatMessageSaveResult original = messages.store(userId, roomId, "reuse", "원문");
        Long otherRoom = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE id <> ? LIMIT 1", Long.class, roomId);

        assertThrows(IllegalArgumentException.class, () -> messages.store(userId, roomId, "reuse", "다른 내용"));
        assertThrows(IllegalArgumentException.class, () -> messages.store(userId, otherRoom, "reuse", "원문"));
        assertEquals(1, count("chat_room_messages"));
        assertEquals("원문", messages.findUnexpiredById(original.message().getId()).orElseThrow().getContent());
    }

    @Test
    void databaseEnforcesUniqueClientIdAndForeignKeys() {
        messages.store(userId, roomId, "unique", "정상");
        String sql = """
                INSERT INTO chat_room_messages (user_id, room_id, client_message_id, content, created_at)
                VALUES (?, ?, ?, '정상', ?)
                """;
        LocalDateTime now = LocalDateTime.ofInstant(START, ZoneOffset.UTC);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, userId, roomId, "unique", now));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, -1L, roomId, "missing-user", now));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, userId, -1L, "missing-room", now));
        assertEquals(1, count("chat_room_messages"));
    }

    @Test
    void validatesBodyAndClientIdAndPreservesThreeHundredUnicodeCharacters() {
        assertThrows(IllegalArgumentException.class, () -> messages.store(userId, roomId, "blank", " \n\t"));
        assertThrows(IllegalArgumentException.class, () -> messages.store(userId, roomId, "long", "가".repeat(301)));
        assertThrows(IllegalArgumentException.class, () -> messages.store(userId, roomId, "", "정상"));
        assertThrows(IllegalArgumentException.class, () -> messages.store(userId, roomId, "a".repeat(101), "정상"));
        ChatMessageSaveResult saved = messages.store(userId, roomId, "emoji", "🎵".repeat(300));

        assertEquals("🎵".repeat(300), messages.findUnexpiredById(saved.message().getId()).orElseThrow().getContent());
    }

    @Test
    void excludesExpiredMessagesAtExactlyTwentyFourHoursBeforeDeletion() {
        Long id = messages.store(userId, roomId, "expiry", "만료 경계").message().getId();
        clock.set(START.plusSeconds(24 * 3600).minusNanos(1000));
        assertTrue(messages.findUnexpiredById(id).isPresent());

        clock.set(START.plusSeconds(24 * 3600));
        assertTrue(messages.findUnexpiredById(id).isEmpty());
        assertEquals(1, count("chat_room_messages"));
        assertThrows(IllegalStateException.class, () -> messages.store(userId, roomId, "expiry", "만료 경계"));
    }

    @Test
    void physicallyDeletesOnlyExpiredMessagesAndKeepsActiveBan() {
        messages.store(userId, roomId, "old", "만료 메시지");
        ChatBan ban = bans.storeAutomaticBan(userId, ChatBanReason.PROFANITY);
        clock.set(START.plusNanos(1000));
        Long freshId = messages.store(userId, roomId, "fresh", "아직 유효").message().getId();
        clock.set(START.plusSeconds(24 * 3600));

        assertEquals(1, messages.deleteExpiredMessages());
        assertEquals(0, messages.deleteExpiredMessages());
        assertEquals(1, count("chat_room_messages"));
        assertTrue(messages.findUnexpiredById(freshId).isPresent());
        assertTrue(bans.hasActiveBan(userId));
        assertEquals(ban.getId(), jdbc.queryForObject("SELECT id FROM chat_bans", Long.class));
    }

    @Test
    void automaticBanStoresSevenDaysAndExpiresWithoutDeletingHistory() {
        ChatBan ban = bans.storeAutomaticBan(userId, ChatBanReason.OBSCENITY);
        assertEquals(ChatBanReason.OBSCENITY, ban.getReason());
        assertNull(ban.getBannedByUser());
        assertEquals(ban.getCreatedAt().plusDays(7), ban.getExpiresAt());
        clock.set(START.plusSeconds(7 * 24 * 3600).minusNanos(1000));
        assertTrue(bans.hasActiveBan(userId));
        clock.set(START.plusSeconds(7 * 24 * 3600));
        assertFalse(bans.hasActiveBan(userId));
        assertEquals(1, count("chat_bans"));

        ChatBan next = bans.storeAutomaticBan(userId, ChatBanReason.PROFANITY);
        assertTrue(next.getId() > ban.getId());
        assertEquals(2, count("chat_bans"));
    }

    @Test
    void concurrentAutomaticBansKeepOneOriginalBanWithoutExtendingExpiry() throws Exception {
        List<ChatBan> results = concurrently(() -> bans.storeAutomaticBan(userId, ChatBanReason.PROFANITY),
                () -> bans.storeAutomaticBan(userId, ChatBanReason.PROFANITY));
        assertEquals(results.get(0).getId(), results.get(1).getId());
        clock.set(START.plusSeconds(3600));
        ChatBan retry = bans.storeAutomaticBan(userId, ChatBanReason.PROFANITY);
        assertEquals(results.get(0).getExpiresAt(), retry.getExpiresAt());
        assertEquals(1, count("chat_bans"));
    }

    @Test
    void databaseRejectsInvalidBanPeriodAndReason() {
        String sql = "INSERT INTO chat_bans (user_id,reason,created_at,expires_at) VALUES (?,?,?,?)";
        LocalDateTime now = LocalDateTime.ofInstant(START, ZoneOffset.UTC);
        DataAccessException invalidPeriod = assertThrows(DataAccessException.class,
                () -> jdbc.update(sql, userId, "PROFANITY", now, now));
        DataAccessException invalidReason = assertThrows(DataAccessException.class,
                () -> jdbc.update(sql, userId, "MESSAGE_BODY", now, now.plusDays(7)));
        assertEquals(3819, assertInstanceOf(SQLException.class, invalidPeriod.getMostSpecificCause()).getErrorCode());
        assertEquals(3819, assertInstanceOf(SQLException.class, invalidReason.getMostSpecificCause()).getErrorCode());
    }

    private Long createUser(String suffix) {
        jdbc.update("INSERT INTO users (email,password_hash,nickname,role) VALUES (?, 'test-only', ?, 'USER')",
                "storage-" + suffix + "@example.com", "store-" + suffix);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "storage-" + suffix + "@example.com");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @SafeVarargs
    private <T> List<T> concurrently(Callable<T>... tasks) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.length)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = java.util.Arrays.stream(tasks).map(task -> executor.submit(() -> {
                await(start);
                return task.call();
            })).toList();
            start.countDown();
            java.util.ArrayList<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for storage test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock storageTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = START;

        void set(Instant value) {
            instant = value;
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
            return instant;
        }
    }
}
