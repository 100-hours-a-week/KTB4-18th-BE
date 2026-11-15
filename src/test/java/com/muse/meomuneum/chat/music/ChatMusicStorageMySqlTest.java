package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ChatMusicStorageMySqlTest {
    private static final String VERSION = "20261109103259";
    private static final AtomicLong IDS = new AtomicLong(800_000_000);
    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static long user;
    private static long music;
    private long room;
    private long otherRoom;
    private TransactionTemplate transaction;
    private ChatMusicStateRepository states;

    @BeforeAll
    static void upgradePreservesRoomsMembershipMessagesAndCatalogAndBackfillsIdle() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("20261109002447").load().migrate();
        jdbc.update("INSERT INTO users (email,password_hash,nickname,role) "
                + "VALUES ('storage@fixture.test','test-only','musicstore','USER')");
        user = jdbc.queryForObject("SELECT id FROM users WHERE email='storage@fixture.test'", Long.class);
        jdbc.update("INSERT INTO music (provider,external_music_id,title,artist_name) "
                + "VALUES ('ITUNES','storage-fixture','song','artist')");
        music = jdbc.queryForObject("SELECT id FROM music WHERE external_music_id='storage-fixture'", Long.class);
        long room = jdbc.queryForObject("SELECT MIN(id) FROM chat_rooms", Long.class);
        jdbc.update("INSERT INTO chat_room_members (user_id,room_id,auth_session_id) VALUES (?,?,?)", user, room, SID);
        jdbc.update("INSERT INTO chat_room_messages (room_id,user_id,client_message_id,content,created_at) "
                + "VALUES (?,?,?,'fixture message',UTC_TIMESTAMP(6))", room, user, UUID.randomUUID().toString());
        jdbc.update("UPDATE chat_rooms SET status='INACTIVE' WHERE id=?", room);
        var before = snapshot();
        var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(VERSION).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_states", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms", Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_states WHERE state<>'IDLE' OR version<>0 "
                + "OR next_sequence_no<>1 OR current_queue_item_id IS NOT NULL OR position_seconds<>0",
                Long.class)).isZero();
    }

    private static Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("rooms", jdbc.queryForList("SELECT * FROM chat_rooms ORDER BY id"),
                "members", jdbc.queryForList("SELECT * FROM chat_room_members ORDER BY id"),
                "messages", jdbc.queryForList("SELECT * FROM chat_room_messages ORDER BY id"),
                "music", jdbc.queryForList("SELECT * FROM music ORDER BY id"),
                "sources", jdbc.queryForList("SELECT * FROM music_playback_sources ORDER BY music_id"));
    }

    @BeforeEach
    void newRooms() {
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        states = new ChatMusicStateRepository(jdbc, manager);
        room = newRoom();
        otherRoom = newRoom();
        states.initializeRoom(room);
        states.initializeRoom(otherRoom);
    }

    @Test
    void initializationIsIdempotentAndNeverResetsExistingStateOrSequence() {
        long item = queue(room, 1);
        jdbc.update("UPDATE music_queue_items SET status='PLAYING',started_at=UTC_TIMESTAMP(6) WHERE id=?", item);
        jdbc.update("UPDATE playback_states SET current_queue_item_id=?,state='PAUSED',position_seconds=42.123,"
                + "version=9,next_sequence_no=10,state_changed_at='2026-11-09 01:02:03.123456' "
                + "WHERE chat_room_id=?", item, room);
        var before = jdbc.queryForMap("SELECT * FROM playback_states WHERE chat_room_id=?", room);
        states.initializeRoom(room);
        assertThat(jdbc.queryForMap("SELECT * FROM playback_states WHERE chat_room_id=?", room)).isEqualTo(before);
        assertThatThrownBy(() -> states.lockRoom(room)).isInstanceOf(IllegalStateException.class);
        var readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(tx -> states.lockRoom(room)))
                .isInstanceOf(IllegalStateException.class);
        var locked = transaction.execute(tx -> states.lockRoom(room));
        assertThat(locked.state()).isEqualTo("PAUSED");
        assertThat(locked.positionSeconds()).isEqualByComparingTo(new BigDecimal("42.123"));
        assertThat(locked.stateChangedAt()).isEqualTo(java.time.Instant.parse("2026-11-09T01:02:03.123456Z"));
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(state_changed_at,'%f') FROM playback_states "
                + "WHERE chat_room_id=?", String.class, room)).isEqualTo("123456");
        assertThatThrownBy(() -> states.initializeRoom(Long.MAX_VALUE))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
    }

    @Test
    void queueEnforcesSequenceReferencesStatusAndSinglePlayingItemPerRoom() {
        long first = queue(room, 1);
        long second = queue(room, 2);
        assertThatThrownBy(() -> queue(room, 1)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> queue(room, 0)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> queue(Long.MAX_VALUE, 1)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET music_id=? WHERE id=?",
                Long.MAX_VALUE, first)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET added_by_user_id=? WHERE id=?",
                Long.MAX_VALUE, first)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET status='waiting' WHERE id=?", first))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET status='PLAYED' WHERE id=?", first))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        jdbc.update("UPDATE music_queue_items SET status='PLAYING',started_at=UTC_TIMESTAMP(6) WHERE id=?", first);
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET status='PLAYING',"
                + "started_at=UTC_TIMESTAMP(6) WHERE id=?", second))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        // The same song can be requested more than once; command identity is a separate constraint.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND music_id=?",
                Long.class, room, music)).isEqualTo(2);
        jdbc.update("UPDATE music_queue_items SET status='CANCELED',finished_at=UTC_TIMESTAMP(6) WHERE id=?", first);
        jdbc.update("UPDATE music_queue_items SET status='PLAYING',started_at=UTC_TIMESTAMP(6) WHERE id=?", second);
    }

    @Test
    void playbackRejectsCrossRoomPointersInvalidIdleNegativePositionAndVersion() {
        long foreign = queue(otherRoom, 1);
        assertThatThrownBy(() -> jdbc.update("UPDATE playback_states SET state='PLAYING',"
                + "current_queue_item_id=? WHERE chat_room_id=?", foreign, room))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        for (String change : List.of("state='PLAYING'", "state='idle'", "position_seconds=-0.001", "version=-1",
                "version=9007199254740992", "next_sequence_no=0", "position_seconds=1")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE playback_states SET " + change + " WHERE chat_room_id=?",
                    room)).isInstanceOf(DataAccessException.class)
                    .satisfies(ChatMusicStorageMySqlTest::constraintError);
        }
        long item = queue(room, 1);
        jdbc.update("UPDATE playback_states SET state='PAUSED',current_queue_item_id=?,position_seconds=1.234 "
                + "WHERE chat_room_id=?", item, room);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM music_queue_items WHERE id=?", item))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        // Cross-table PLAYING correspondence is a service transaction invariant, not a CHECK claim.
    }

    @Test
    void receiptScopesUuidAndBoundsCompletedResultsWithoutDependingOnViewRetention() {
        String id = UUID.randomUUID().toString();
        receipt(room, id, "{\"data\":{}}", "a".repeat(64));
        assertThatThrownBy(() -> receipt(room, id, "{}", "b".repeat(64)))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        receipt(otherRoom, id, "{}", "a".repeat(64));
        assertThatThrownBy(() -> receipt(room, "not-a-uuid", "{}", "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> receipt(room, UUID.randomUUID().toString(), "not-json", "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> receipt(room, UUID.randomUUID().toString(), "[]", "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> receipt(room, UUID.randomUUID().toString(), "{}", "not-hmac"))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        String large = "{\"data\":\"" + "한".repeat(6000) + "\"}";
        assertThatThrownBy(() -> receipt(room, UUID.randomUUID().toString(), large, "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> jdbc.update("UPDATE chat_music_command_receipts SET expires_at=completed_at "
                + "WHERE room_id=?", room)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
    }

    @Test
    void multipleTaskTransactionsAllocateDistinctSequencesAndRollbackAllRelatedWrites() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 16).mapToObj(n -> pool.submit(() -> {
                start.await();
                transaction.executeWithoutResult(tx -> {
                    var state = states.lockRoom(room);
                    queue(room, state.nextSequenceNo());
                    jdbc.update("UPDATE playback_states SET next_sequence_no=next_sequence_no+1,version=version+1 "
                            + "WHERE chat_room_id=?", room);
                });
                return true;
            })).toList();
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(jdbc.queryForList("SELECT sequence_no FROM music_queue_items WHERE room_id=? ORDER BY sequence_no",
                Long.class, room)).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 16)
                        .boxed().toList());
        var before = jdbc.queryForMap("SELECT * FROM playback_states WHERE chat_room_id=?", room);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            var state = states.lockRoom(room);
            queue(room, state.nextSequenceNo());
            jdbc.update("UPDATE playback_states SET next_sequence_no=next_sequence_no+1,version=version+1 "
                    + "WHERE chat_room_id=?", room);
            receipt(room, UUID.randomUUID().toString(), "{}", "a".repeat(64));
            outbox(room, 17, "QUEUE_UPDATED", "invalid-json");
        })).isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThat(jdbc.queryForMap("SELECT * FROM playback_states WHERE chat_room_id=?", room)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=?", Long.class, room))
                .isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_command_receipts WHERE room_id=?",
                Long.class, room)).isZero();
        transaction.executeWithoutResult(tx -> {
            var state = states.lockRoom(room);
            queue(room, state.nextSequenceNo());
            jdbc.update("UPDATE playback_states SET next_sequence_no=next_sequence_no+1,version=version+1 "
                    + "WHERE chat_room_id=?", room);
            receipt(room, UUID.randomUUID().toString(), "{}", "a".repeat(64));
            jdbc.update("UPDATE chat_music_command_receipts SET result_version=17 WHERE room_id=?", room);
            outbox(room, 17, "QUEUE_UPDATED", "{}");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=?", Long.class, room))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT version FROM playback_states WHERE chat_room_id=?", Long.class, room))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=?", Long.class, room))
                .isEqualTo(1);
    }

    @Test
    void outboxAllowsTwoReferencesPerVersionButRejectsDuplicatesAndSupportsFencedClaim() throws Exception {
        String event = outbox(room, 1, "QUEUE_UPDATED", "{}");
        outbox(room, 1, "PLAYBACK_UPDATED", "{}");
        assertThatThrownBy(() -> outbox(room, 1, "QUEUE_UPDATED", "{}"))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> outbox(room, 2, "PLAYBACK_SYNC", "{}"))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> outbox(room, 2, "QUEUE_UPDATED", "[]"))
                .isInstanceOf(DataAccessException.class).satisfies(ChatMusicStorageMySqlTest::constraintError);
        assertThatThrownBy(() -> jdbc.update("UPDATE chat_music_outbox SET claim_token=? WHERE event_id=?",
                UUID.randomUUID().toString(), event)).isInstanceOf(DataAccessException.class)
                .satisfies(ChatMusicStorageMySqlTest::constraintError);
        String winner = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claim(event, winner));
            var second = pool.submit(() -> claim(event, UUID.randomUUID().toString()));
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        String token = jdbc.queryForObject("SELECT claim_token FROM chat_music_outbox WHERE event_id=?",
                String.class, event);
        jdbc.update("UPDATE chat_music_outbox SET claim_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) "
                + "WHERE event_id=?", event);
        String replacement = UUID.randomUUID().toString();
        assertThat(claim(event, replacement)).isEqualTo(1);
        assertThat(finish(event, token)).isZero();
        assertThat(finish(event, replacement)).isEqualTo(1);
    }

    @Test
    void storageIndexesSupportWaitingExpiryAndDispatchQueries() {
        queue(room, 1);
        outbox(room, 1, "QUEUE_UPDATED", "{}");
        receipt(room, UUID.randomUUID().toString(), "{}", "a".repeat(64));
        assertThat(jdbc
                .queryForList(
                        "EXPLAIN FORMAT=JSON SELECT id FROM music_queue_items FORCE INDEX (idx_music_queue_waiting) "
                                + "WHERE room_id=? AND status='WAITING' ORDER BY sequence_no LIMIT 20",
                        room)
                .toString())
                .contains("idx_music_queue_waiting");
        assertThat(jdbc
                .queryForList(
                        "EXPLAIN FORMAT=JSON SELECT event_id FROM chat_music_outbox "
                                + "FORCE INDEX (idx_music_outbox_dispatch) WHERE environment='test' "
                                + "AND status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6) "
                                + "ORDER BY next_attempt_at LIMIT 20")
                .toString()).contains("idx_music_outbox_dispatch");
        assertThat(jdbc.queryForList(
                "EXPLAIN FORMAT=JSON SELECT command_id FROM chat_music_command_receipts "
                        + "FORCE INDEX (idx_music_receipt_expiry) "
                        + "WHERE expires_at<DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 HOUR) ORDER BY expires_at LIMIT 20")
                .toString())
                .contains("idx_music_receipt_expiry");
    }

    private static void constraintError(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) root).getErrorCode()).isIn(1062, 1451, 1452, 3819, 3140, 3141);
    }

    private long newRoom() {
        long id = IDS.incrementAndGet();
        long parent = jdbc.queryForObject("SELECT MIN(id) FROM regions WHERE level='SIDO'", Long.class);
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level) VALUES (?,?,?,'fixture','SIGUNGU')", id,
                parent, "storage-" + id);
        jdbc.update("INSERT INTO chat_rooms (region_id) VALUES (?)", id);
        return jdbc.queryForObject("SELECT id FROM chat_rooms WHERE region_id=?", Long.class, id);
    }

    private long queue(long targetRoom, long sequence) {
        jdbc.update("INSERT INTO music_queue_items (room_id,music_id,added_by_user_id,sequence_no) VALUES (?,?,?,?)",
                targetRoom, music, user, sequence);
        return jdbc.queryForObject("SELECT id FROM music_queue_items WHERE room_id=? AND sequence_no=?",
                Long.class, targetRoom, sequence);
    }

    private void receipt(long targetRoom, String command, String body, String fingerprint) {
        jdbc.update("""
                INSERT INTO chat_music_command_receipts
                (environment,user_id,room_id,command_id,auth_session_id,membership_id,view_id,command_type,
                 key_id,fingerprint,result_version,http_status,result_body,expires_at)
                VALUES ('test',?,?,?,?,1,?,'ADD','test-key',?,1,201,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR))
                """, user, targetRoom, command, SID, UUID.randomUUID().toString(), fingerprint, body);
    }

    private String outbox(long targetRoom, long version, String type, String body) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO chat_music_outbox
                (event_id,environment,epoch,producer_id,room_id,room_seq,music_version,event_type,event_body,expires_at)
                VALUES (?,'test',1,?,?,1,?,?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR))
                """, id, UUID.randomUUID().toString(), targetRoom, version, type, body);
        return id;
    }

    private int claim(String event, String token) {
        return jdbc.update("UPDATE chat_music_outbox SET claim_token=?,claim_until=DATE_ADD(UTC_TIMESTAMP(6), "
                + "INTERVAL 5 SECOND),attempts=attempts+1 WHERE event_id=? AND status='PENDING' "
                + "AND (claim_until IS NULL OR claim_until<UTC_TIMESTAMP(6))", token, event);
    }

    private int finish(String event, String token) {
        return jdbc.update("UPDATE chat_music_outbox SET status='SENT',sent_at=UTC_TIMESTAMP(6),claim_token=NULL,"
                + "claim_until=NULL WHERE event_id=? AND claim_token=? AND status='PENDING' "
                + "AND claim_until>UTC_TIMESTAMP(6)", event, token);
    }
}
