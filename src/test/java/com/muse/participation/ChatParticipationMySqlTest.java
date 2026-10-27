package com.muse.participation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatMessageOutboundGuard;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.message.domain.ChatRoomMessage;
import com.muse.meomuneum.chat.message.dto.ChatMessageEvent;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.policy.ChatMessageFingerprint;
import com.muse.meomuneum.chat.message.policy.ChatMessageRejection;
import com.muse.meomuneum.chat.message.query.ChatCursorCodec;
import com.muse.meomuneum.chat.message.query.ChatQueryCursor;
import com.muse.meomuneum.chat.message.query.ChatQueryException;
import com.muse.meomuneum.chat.message.query.DistributedChatMessageQueryService;
import com.muse.meomuneum.chat.message.relay.ChatMessageOutboxStore;
import com.muse.meomuneum.chat.message.relay.ChatMessageRedisEvent;
import com.muse.meomuneum.chat.message.relay.ChatMessageRelay;
import com.muse.meomuneum.chat.message.service.ChatMessageSaveResult;
import com.muse.meomuneum.chat.message.service.ChatMessageStorageService;
import com.muse.meomuneum.chat.message.service.DistributedChatMessageService;
import com.muse.meomuneum.chat.participation.ChatParticipationProjection;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;
import com.muse.meomuneum.chat.participation.RedisChatParticipationProjection;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
@Testcontainers
class ChatParticipationMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("participation").withUsername("test").withPassword("isolated-test-password")
            .withCommand("mysqld", "--skip-log-bin");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "isolated-redis-password");
    private static JdbcTemplate jdbc;
    private static HikariDataSource dataSource;
    private static long room;
    private static long region;

    @BeforeAll
    static void migrate() {
        Flyway old = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("20261027131057").load();
        old.migrate();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        dataSource.setMaximumPoolSize(16);
        jdbc = new JdbcTemplate(dataSource);
        var before = jdbc.queryForList("SELECT * FROM chat_rooms ORDER BY id");
        Flyway latest = Flyway.configure().dataSource(dataSource).load();
        assertEquals(1, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted);
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertEquals(before, jdbc.queryForList("SELECT * FROM chat_rooms ORDER BY id"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_runtime", Integer.class));
        region = jdbc.queryForObject("SELECT id FROM regions WHERE code='41135'", Long.class);
        room = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE region_id=?", Long.class, region);
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void lastSeatRaceCountsAccountsOnceAcrossInstancesAndRedisCannotGrantCapacity() throws Exception {
        String environment = "race";
        var seedService = service(environment, snapshot -> {
        });
        long seed = user();
        ChatRoomJoinResult start = seedService.join(seed, UUID.randomUUID().toString(), room, claims(seed));
        // 999 different accounts, valid unowned reservations. No fake membership is counted as online.
        for (int i = 0; i < 998; i++) {
            long user = user();
            String sid = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO chat_room_members(user_id,room_id,auth_session_id) VALUES (?,?,?)", user, room,
                    sid);
            long member = jdbc.queryForObject("SELECT id FROM chat_room_members WHERE auth_session_id=?", Long.class,
                    sid);
            String view = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO chat_view_sessions(id,user_id,room_id,membership_id,created_at,updated_at)
                    VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, view, user, room, member);
            jdbc.update(
                    """
                                INSERT INTO chat_view_runtime
                            (view_id,environment,epoch,user_id,room_id,membership_id,state,deadline)
                                VALUES (?,?,1,?,?,?,'RESERVED',TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)))
                                """,
                    view, environment, user, room, member);
        }
        jdbc.update("UPDATE chat_view_runtime SET deadline=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) "
                + "WHERE environment=?", environment);
        var first = service(environment, snapshot -> {
        });
        var second = service(environment, snapshot -> {
        });
        var ready = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        var full = new AtomicInteger();
        var overloaded = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = IntStream.range(0, 8).mapToObj(i -> {
                long next = user();
                return pool.submit(() -> {
                    ready.await();
                    try {
                        (i % 2 == 0 ? first : second).join(next, UUID.randomUUID().toString(), room, claims(next));
                        admitted.incrementAndGet();
                    } catch (ChatRoomException rejected) {
                        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, rejected.getErrorCode());
                        full.incrementAndGet();
                    } catch (ChatParticipationUnavailableException boundedTimeout) {
                        // The contract allows 503 for the 1s lock/2s transaction budget; it must leave no seat.
                        overloaded.incrementAndGet();
                    }
                    return null;
                });
            }).toList();
            ready.countDown();
            for (var attempt : attempts) {
                attempt.get();
            }
        }
        assertEquals(1, admitted.get());
        assertEquals(7, full.get() + overloaded.get());
        long rejectedUser = user();
        var rejection = assertThrows(ChatRoomException.class,
                () -> first.join(rejectedUser, UUID.randomUUID().toString(), room, claims(rejectedUser)));
        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, rejection.getErrorCode());
        assertEquals(1000, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_runtime WHERE environment='race'",
                Integer.class));
        assertEquals(1000, first.snapshot(room).occupied());
        second.join(seed, UUID.randomUUID().toString(), room, claims(seed));
        assertEquals(1000, second.snapshot(room).occupied());
        assertEquals(0, second.snapshot(room).connected());
        assertTrue(start.membership().viewSessionId() != null);
    }

    @Test
    void crossTaskClaimGraceExpiryLateDisconnectTabLeaveAndOtherSidStayIsolated() {
        var first = service("lifecycle", snapshot -> {
        });
        var second = service("lifecycle", snapshot -> {
        });
        long user = user();
        String sid = UUID.randomUUID().toString();
        var joined = first.join(user, sid, room, claims(user)).membership();
        String view = joined.viewSessionId();
        long member = joined.membershipId();
        assertThrows(ChatRoomException.class,
                () -> second.claim(user, UUID.randomUUID().toString(), room, member, view, "wrong-sid"));
        second.claim(user, sid, room, member, view, "socket-b");
        assertThrows(ChatRoomException.class, () -> first.claim(user, sid, room, member, view, "duplicate"));
        second.ready(user, room, member, view, "socket-b");
        assertEquals(1, first.snapshot(room).connected());
        // Starting a third task cannot close a live task's verified range.
        service("lifecycle", snapshot -> {
        });
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? AND ends_at IS NULL
                """, Integer.class, view));
        second.disconnected(user, room, member, view, "socket-b");
        assertEquals(1, first.snapshot(room).occupied());
        assertEquals(0, first.snapshot(room).connected());
        first.claim(user, sid, room, member, view, "socket-a");
        first.ready(user, room, member, view, "socket-a");
        second.disconnected(user, room, member, view, "socket-b");
        assertTrue(first.socketValid(user, member, "socket-a"));
        var tab = second.join(user, sid, room, claims(user)).membership();
        var other = second.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        first.leave(user, sid, room, member, view);
        first.leave(user, sid, room, member, view); // Idempotent even after another Task observed the first leave.
        assertEquals(1, first.snapshot(room).occupied());
        first.end(user, sid, null, null);
        assertEquals("LEFT", state(tab.viewSessionId()));
        assertEquals("RESERVED", state(other.viewSessionId()));
        jdbc.update("UPDATE chat_view_runtime SET deadline=UTC_TIMESTAMP(6) WHERE view_id=?", other.viewSessionId());
        assertEquals(0, first.snapshot(room).occupied());
        first.sweep();
        assertEquals("OFFLINE", state(other.viewSessionId()));
        second.claim(user, membershipSid(other.membershipId()), room, other.membershipId(), other.viewSessionId(),
                "new-gap");
        second.ready(user, room, other.membershipId(), other.viewSessionId(), "new-gap");
        assertEquals(1, first.snapshot(room).occupied());
    }

    @Test
    void expiredOwnerCannotWriteAndRecoveryClosesOnlyItsLastVerifiedBoundary() {
        var old = service("lease", snapshot -> {
        });
        var healthy = service("lease", snapshot -> {
        });
        long user = user();
        var joined = old.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        old.claim(user, membershipSid(joined.membershipId()), room, joined.membershipId(), joined.viewSessionId(),
                "old");
        old.ready(user, room, joined.membershipId(), joined.viewSessionId(), "old");

        jdbc.update(
                "UPDATE chat_runtime_tasks SET lease_until=UTC_TIMESTAMP(6) WHERE environment='lease' AND task_id=?",
                old.taskId());
        assertFalse(old.socketValid(user, joined.membershipId(), "old"));
        assertThrows(ChatParticipationUnavailableException.class,
                () -> old.checkpoint(user, room, joined.membershipId(), joined.viewSessionId(), "old"));
        healthy.sweep();
        assertEquals("FENCED", jdbc.queryForObject("SELECT state FROM chat_runtime_tasks WHERE environment='lease' "
                + "AND task_id=?", String.class, old.taskId()));
        assertEquals("OFFLINE", state(joined.viewSessionId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_view_intervals WHERE view_session_id=? AND ends_at=verified_at
                  AND end_message_id=verified_through_message_id
                """, Integer.class, joined.viewSessionId()));
        healthy.claim(user, membershipSid(joined.membershipId()), room, joined.membershipId(), joined.viewSessionId(),
                "new");
        healthy.ready(user, room, joined.membershipId(), joined.viewSessionId(), "new");
        assertThrows(ChatParticipationUnavailableException.class,
                () -> old.disconnected(user, room, joined.membershipId(), joined.viewSessionId(), "old"));
        assertTrue(healthy.socketValid(user, joined.membershipId(), "new"));
    }

    @Test
    void projectionFailureDoesNotRollBackCommittedSeatAndInvalidLocationLeavesNoRows() {
        var calls = new AtomicInteger();
        var service = service("failure", snapshot -> {
            calls.incrementAndGet();
            throw new IllegalStateException("synthetic Redis down");
        });
        long user = user();
        var invalid = new LocationResolutionClaims(user, 41L, "41", region, "invalid", Instant.now().plusSeconds(60));
        assertThrows(ChatRoomException.class, () -> service.join(user, UUID.randomUUID().toString(), room, invalid));
        assertEquals(0, calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_members WHERE user_id=?", Integer.class,
                user));
        assertThrows(ChatParticipationUnavailableException.class,
                () -> service.join(user, UUID.randomUUID().toString(), room, claims(user)));
        assertFalse(service.available());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_view_runtime WHERE user_id=?", Integer.class,
                user));
        assertEquals(1, service.snapshot(room).occupied());
    }

    @Test
    void realRedisCasRejectsReverseSnapshotsAndMissingProjectionDoesNotChangeDbSeats() {
        var properties = new ChatRedisProperties("cas", REDIS.getHost(), REDIS.getMappedPort(6379),
                "default", "isolated-redis-password", false, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1024, "");
        try (var redis = new ChatRedisConnection(properties)) {
            var channels = new ChatRedisChannels(properties);
            var projection = new RedisChatParticipationProjection(redis, channels);
            var service = service("cas", projection);
            long user = user();
            var joined = service.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
            var before = service.snapshot(room);
            service.claim(user, membershipSid(joined.membershipId()), room, joined.membershipId(),
                    joined.viewSessionId(), "socket");
            service.ready(user, room, joined.membershipId(), joined.viewSessionId(), "socket");
            var after = service.snapshot(room);
            projection.write(before);
            String key = channels.roomStateKey(room, 1, "presence");
            assertEquals(Long.toString(after.version()), redis.commands().sync().hget(key, "version"));
            redis.commands().sync().del(key);
            assertEquals(1, service.snapshot(room).occupied());
            service.refreshProjections();
            assertEquals(Long.toString(after.version()), redis.commands().sync().hget(key, "version"));
        }
    }

    @Test
    void confirmedRegionMoveCommitsPreviousDepartureWhenTargetIsFullAndKeepsOtherSid() {
        var service = service("moving", snapshot -> {
        });
        long user = user();
        String movingSid = UUID.randomUUID().toString();
        var moving = service.join(user, movingSid, room, claims(user)).membership();
        var staying = service.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        long target = jdbc.queryForObject("""
                SELECT r.id FROM chat_rooms r JOIN regions g ON g.id=r.region_id
                WHERE r.id<>? AND g.parent_id=(SELECT parent_id FROM regions WHERE id=?) LIMIT 1
                """, Long.class, room, region);
        jdbc.update("UPDATE chat_rooms SET capacity=1 WHERE id=?", target);
        long blocker = user();
        long targetRegion = jdbc.queryForObject("SELECT region_id FROM chat_rooms WHERE id=?", Long.class, target);
        String targetCode = jdbc.queryForObject("SELECT code FROM regions WHERE id=?", String.class, targetRegion);
        var location = new LocationResolutionClaims(user, 41L, "41", targetRegion, targetCode,
                Instant.now().plusSeconds(60));
        service.join(blocker, UUID.randomUUID().toString(), target,
                new LocationResolutionClaims(blocker, 41L, "41", targetRegion, targetCode,
                        Instant.now().plusSeconds(60)));
        var full = assertThrows(ChatRoomException.class, () -> service.join(user, movingSid, target, location));
        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, full.getErrorCode());
        assertEquals("LEFT", state(moving.viewSessionId()));
        assertEquals("RESERVED", state(staying.viewSessionId()));
        assertEquals(1, service.snapshot(room).occupied());
        assertEquals(1, service.snapshot(target).occupied());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_members "
                + "WHERE user_id=? AND auth_session_id=? AND deleted_at IS NULL", Integer.class, user, movingSid));
    }

    @Test
    void realMysqlLockTimeoutRollsBackTheWholeReservation() throws Exception {
        var service = service("timeout", snapshot -> {
        });
        long initial = user();
        service.join(initial, UUID.randomUUID().toString(), room, claims(initial));
        long user = user();
        try (var held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (var lock = held.prepareStatement("""
                    SELECT room_seq FROM chat_room_runtime WHERE environment='timeout' AND room_id=? FOR UPDATE
                    """)) {
                lock.setLong(1, room);
                lock.executeQuery().close();
                assertThrows(ChatParticipationUnavailableException.class,
                        () -> service.join(user, UUID.randomUUID().toString(), room, claims(user)));
            } finally {
                held.rollback();
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_members WHERE user_id=?", Integer.class,
                user));
        assertEquals(1, service.snapshot(room).occupied());
    }

    @Test
    void remotePagesFreezeRangesAndDoNotCheckpointOtherOwner() {
        var owner = service("read", snapshot -> {
        });
        var remote = service("read", snapshot -> {
        });
        var queries = queries(remote);
        long user = user();
        var older = owner.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(owner, user, older, "older");
        var hidden = send(owner, user, older.membershipId(), "older");
        var current = owner.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(owner, user, current, "current");
        assertThrows(ChatRoomException.class,
                () -> remote.read(user, room, current.membershipId(), current.viewSessionId(), context -> {
                    jdbc.update("UPDATE chat_runtime_tasks SET lease_until=UTC_TIMESTAMP(6) WHERE task_id=?",
                            owner.taskId());
                    return null;
                }));
        var first = send(owner, user, current.membershipId(), "current");
        var second = send(owner, user, current.membershipId(), "current");
        var before = jdbc.queryForList("SELECT verified_at,verified_through_room_seq FROM chat_view_intervals "
                + "WHERE view_session_id=?", current.viewSessionId());
        assertTrue(queries.query(user, room, current.membershipId(), current.viewSessionId(), "history", null, 10)
                .items().isEmpty());
        assertEquals(before, jdbc.queryForList("SELECT verified_at,verified_through_room_seq FROM chat_view_intervals "
                + "WHERE view_session_id=?", current.viewSessionId()));
        owner.checkpoint(user, room, current.membershipId(), current.viewSessionId(), "current");
        var page = queries.query(user, room, current.membershipId(), current.viewSessionId(), "history", null, 1);
        assertEquals(second.messageId(), page.items().getFirst().messageId());
        assertTrue(page.hasNext());
        owner.checkpoint(user, room, older.membershipId(), older.viewSessionId(), "older");
        var next = queries.query(user, room, current.membershipId(), current.viewSessionId(), "history",
                page.nextCursor(), 10);
        assertEquals(List.of(first.messageId()), next.items().stream().map(m -> m.messageId()).toList());
        var decoded = codec().decode(page.nextCursor());
        var skewedCodec = new ChatCursorCodec(new ObjectMapper(),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(48)),
                "synthetic-cursor-key-at-least-thirty-two-bytes");
        var skewedQueries = new DistributedChatMessageQueryService(remote,
                new NamedParameterJdbcTemplate(jdbc), skewedCodec);
        assertEquals(next.items(), skewedQueries.query(user, room, current.membershipId(), current.viewSessionId(),
                "history", page.nextCursor(), 10).items());
        var previousEpoch = new ChatQueryCursor(decoded.userId(), decoded.roomId(), decoded.membershipId(),
                decoded.viewId(), decoded.mode(), decoded.snapshot(), decoded.position(), decoded.expiresAt(),
                decoded.historyAsOf(), decoded.snapshotId(), decoded.epoch() - 1, decoded.generation());
        assertThrows(ChatQueryException.class,
                () -> queries.query(user, room, current.membershipId(), current.viewSessionId(), "history",
                        codec().encode(previousEpoch), 10));
        jdbc.update("UPDATE chat_query_snapshots SET expires_at=UTC_TIMESTAMP(6) WHERE id=?", decoded.snapshotId());
        assertThrows(ChatQueryException.class,
                () -> queries.query(user, room, current.membershipId(), current.viewSessionId(), "history",
                        page.nextCursor(), 10));
        jdbc.update("UPDATE chat_query_snapshots SET expires_at=TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6)) WHERE id=?",
                decoded.snapshotId());
        var fresh = queries.query(user, room, current.membershipId(), current.viewSessionId(), "history", null, 10);
        assertEquals(3, fresh.items().size());
        assertEquals(hidden.messageId(), queries.byClientId(user, room, current.membershipId(), current.viewSessionId(),
                hidden.clientMessageId()).messageId());
        var oldCursor = new ChatQueryCursor(user, room, current.membershipId(),
                current.viewSessionId(), "history", second.messageId(), 0, Instant.now().plusSeconds(60).toEpochMilli(),
                LocalDateTime.now(ZoneOffset.UTC).toString());
        assertThrows(ChatQueryException.class,
                () -> queries.query(user, room, current.membershipId(), current.viewSessionId(), "history",
                        codec().encode(oldCursor), 10));
        owner.disconnected(user, room, current.membershipId(), current.viewSessionId(), "current");
        remote.claim(user, membershipSid(current.membershipId()), room, current.membershipId(), current.viewSessionId(),
                "new");
        remote.ready(user, room, current.membershipId(), current.viewSessionId(), "new");
        assertThrows(ChatQueryException.class,
                () -> queries.query(user, room, current.membershipId(), current.viewSessionId(), "history",
                        page.nextCursor(), 10));
        assertEquals(2, queries.query(user, room, current.membershipId(), current.viewSessionId(), "recovery", null, 10)
                .items().size());
    }

    @Test
    void accountHistorySurvivesReloginButAbsenceRetentionAndWrongCurrentViewStayExcluded() {
        var owner = service("absence", snapshot -> {
        });
        var remote = service("absence", snapshot -> {
        });
        long user = user();
        String sid = UUID.randomUUID().toString();
        var original = owner.join(user, sid, room, claims(user)).membership();
        connect(owner, user, original, "old-login");
        var visible = send(owner, user, original.membershipId(), "old-login");
        owner.checkpoint(user, room, original.membershipId(), original.viewSessionId(), "old-login");
        owner.end(user, sid, null, null);
        long outsider = user();
        var other = remote.join(outsider, UUID.randomUUID().toString(), room, claims(outsider)).membership();
        connect(remote, outsider, other, "outside");
        var absent = send(remote, outsider, other.membershipId(), "outside");
        var login = owner.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(owner, user, login, "new-login");
        var history = queries(remote).query(user, room, login.membershipId(), login.viewSessionId(), "history", null,
                10);
        assertEquals(List.of(visible.messageId()), history.items().stream().map(m -> m.messageId()).toList());
        assertFalse(history.items().stream().anyMatch(m -> m.messageId().equals(absent.messageId())));
        assertTrue(queries(remote).query(user, room, login.membershipId(), login.viewSessionId(), "recovery", null, 10)
                .items().isEmpty());
        assertThrows(ChatRoomException.class, () -> queries(remote).query(user, room, login.membershipId(),
                original.viewSessionId(), "history", null, 10));
        assertThrows(ChatRoomException.class, () -> queries(remote).query(outsider, room, login.membershipId(),
                login.viewSessionId(), "history", null, 10));
        jdbc.update("UPDATE chat_room_messages SET created_at=TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6)) WHERE id=?",
                visible.messageId());
        assertThrows(ChatQueryException.class,
                () -> queries(remote).byClientId(user, room, login.membershipId(), login.viewSessionId(),
                        visible.clientMessageId()));
    }

    @Test
    void delayedOutboundFrameCannotCrossUnsubscribeAndNewInterval() {
        var owner = service("outbound", snapshot -> {
        });
        long user = user();
        String sid = UUID.randomUUID().toString();
        var joined = owner.join(user, sid, room, claims(user)).membership();
        connect(owner, user, joined, "queued");
        var message = send(owner, user, joined.membershipId(), "queued");
        var presence = mock(ChatPresenceRegistry.class);
        when(presence.hasRoomSubscription("queued", room)).thenReturn(true);
        var guard = new ChatMessageOutboundGuard(presence);
        var registry = new ChatAuthSessionRegistry();
        registry.register("queued", new ChatAuthSessionRegistry.Context(user, sid));
        guard.setCoreVerifier(mock(ChatSessionVerifier.class), registry);
        guard.setParticipation(owner);
        var headers = SimpMessageHeaderAccessor.create(
                SimpMessageType.MESSAGE);
        headers.setSessionId("queued");
        headers.setHeader("chatRoomId", room);
        headers.setHeader("chatMessageId", message.messageId());
        headers.setHeader("chatRecipients", Set.of("queued"));
        var frame = MessageBuilder.createMessage(new byte[0],
                headers.getMessageHeaders());
        assertEquals(frame, guard.beforeHandle(frame, null, null));
        owner.paused(user, room, joined.membershipId(), joined.viewSessionId(), "queued");
        owner.ready(user, room, joined.membershipId(), joined.viewSessionId(), "queued");
        assertEquals(null, guard.beforeHandle(frame, null, null));
        assertFalse(owner.permitsMessage(user, "queued", room, message.messageId()));
        var fresh = send(owner, user, joined.membershipId(), "queued");
        assertTrue(owner.permitsMessage(user, "queued", room, fresh.messageId()));
    }

    @Test
    void roomLockOrdersMessageCommitBeforeFollowingSubscription() throws Exception {
        var writer = service("ordering", snapshot -> {
        });
        var reader = service("ordering", snapshot -> {
        });
        long user = user();
        long listener = user();
        var writing = writer.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(writer, user, writing, "writer");
        var reading = reader.join(listener, UUID.randomUUID().toString(), room, claims(listener)).membership();
        reader.claim(listener, membershipSid(reading.membershipId()), room, reading.membershipId(),
                reading.viewSessionId(), "reader");
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var message = pool.submit(() -> writer.writeMessage(user, room, writing.membershipId(), "writer", () -> {
                var saved = insert(user);
                inserted.countDown();
                try {
                    assertTrue(commit.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException stopped) {
                    throw new AssertionError(stopped);
                }
                return saved;
            }));
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            var subscribe = pool.submit(
                    () -> reader.ready(listener, room, reading.membershipId(), reading.viewSessionId(), "reader"));
            Thread.sleep(100);
            assertFalse(subscribe.isDone());
            commit.countDown();
            long id = message.get().messageId();
            subscribe.get();
            long messageSeq = jdbc.queryForObject(
                    "SELECT room_seq FROM chat_message_order WHERE environment='ordering' AND message_id=?", Long.class,
                    id);
            long startSeq = jdbc.queryForObject(
                    "SELECT start_after_room_seq FROM chat_view_intervals WHERE view_session_id=? AND ends_at IS NULL",
                    Long.class, reading.viewSessionId());
            assertTrue(startSeq > messageSeq);
            assertTrue(queries(writer)
                    .query(listener, room, reading.membershipId(), reading.viewSessionId(), "history", null, 10).items()
                    .isEmpty());
        } finally {
            commit.countDown();
        }
    }

    @Test
    void stoppedCutoverMapsLegacyIdBoundsWithoutChangingHistoricalRows() {
        var service = service("legacy", snapshot -> {
        });
        long user = user();
        var message = insert(user);
        jdbc.update("UPDATE chat_room_messages SET created_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)) WHERE id=?",
                message.messageId());
        String sid = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO chat_room_members(user_id,room_id,auth_session_id,created_at,deleted_at)
                VALUES (?,?,?,TIMESTAMPADD(SECOND,-30,UTC_TIMESTAMP(6)),TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6)))
                """, user, room, sid);
        long member = jdbc.queryForObject("SELECT id FROM chat_room_members WHERE auth_session_id=?", Long.class, sid);
        String view = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO chat_view_sessions(id,user_id,room_id,membership_id,created_at,updated_at,ended_at)
                VALUES (?,?,?,?,TIMESTAMPADD(SECOND,-30,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6),
                    TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6)))
                """, view, user, room, member);
        jdbc.update("""
                INSERT INTO chat_view_intervals(view_session_id,process_id,started_at,start_after_message_id,
                    verified_at,verified_through_message_id,ends_at,end_message_id,end_reason)
                VALUES (?,?,TIMESTAMPADD(SECOND,-30,UTC_TIMESTAMP(6)),0,TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6)),?,
                    TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6)),?,'LEFT')
                """, view, UUID.randomUUID().toString(), message.messageId(), message.messageId());
        var before = jdbc.queryForMap("""
                SELECT started_at,start_after_message_id,verified_at,verified_through_message_id,ends_at,end_message_id
                FROM chat_view_intervals WHERE view_session_id=?
                """, view);
        var expected = jdbc.queryForList("""
                SELECT m.id FROM chat_room_messages m JOIN chat_view_intervals i ON i.view_session_id=?
                WHERE m.room_id=? AND m.id>i.start_after_message_id AND m.id<=i.end_message_id
                    AND m.created_at>=i.started_at ORDER BY m.id DESC
                """, Long.class, view, room);
        var current = service.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(service, user, current, "after-cutover");
        assertEquals(before, jdbc.queryForMap("""
                SELECT started_at,start_after_message_id,verified_at,verified_through_message_id,ends_at,end_message_id
                FROM chat_view_intervals WHERE view_session_id=?
                """, view));
        var restored = queries(service).query(user, room, current.membershipId(), current.viewSessionId(), "history",
                null, 10);
        assertEquals(expected, restored.items().stream().map(m -> m.messageId()).toList());
        assertTrue(expected.contains(message.messageId()));
    }

    @Test
    void physicalMessageCleanupCannotMoveAnActiveVerifiedIdBoundaryBackwards() {
        var owner = service("cleanup", snapshot -> {
        });
        long user = user();
        var view = owner.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(owner, user, view, "active");
        var removed = send(owner, user, view.membershipId(), "active");
        owner.checkpoint(user, room, view.membershipId(), view.viewSessionId(), "active");
        jdbc.update("DELETE FROM chat_room_messages WHERE id=?", removed.messageId());
        owner.checkpoint(user, room, view.membershipId(), view.viewSessionId(), "active");
        assertEquals(removed.messageId(), jdbc.queryForObject("""
                SELECT verified_through_message_id FROM chat_view_intervals
                WHERE view_session_id=? AND ends_at IS NULL
                """, Long.class, view.viewSessionId()));
        var next = send(owner, user, view.membershipId(), "active");
        owner.checkpoint(user, room, view.membershipId(), view.viewSessionId(), "active");
        assertEquals(List.of(next.messageId()),
                queries(owner).query(user, room, view.membershipId(), view.viewSessionId(),
                        "history", null, 10).items().stream().map(m -> m.messageId()).toList());
    }

    private static final String FINGERPRINT_KEY = "v1:" + java.util.Base64.getEncoder().encodeToString(
            "synthetic-chat-message-fingerprint-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void durableReceiptSurvivesOwnerChangeAndPolicyIsSharedAcrossTasksAndKeyRotation() {
        String env = "message-policy";
        var owner = service(env, snapshot -> {
        });
        var peer = service(env, snapshot -> {
        });
        var firstService = messages(owner, env, FINGERPRINT_KEY, false);
        long user = user();
        var joined = owner.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(owner, user, joined, "message-first");
        String client = UUID.randomUUID().toString();
        var first = firstService.send(user, room, joined.membershipId(), "message-first", client, "original", "masked");
        assertFalse(first.deduplicated());
        assertEquals("masked", jdbc.queryForObject("SELECT content FROM chat_room_messages WHERE id=?",
                String.class, first.message().messageId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE environment=?",
                Integer.class, env));
        owner.disconnected(user, room, joined.membershipId(), joined.viewSessionId(), "message-first");
        connect(peer, user, joined, "message-peer");
        String rotatedKey = "v2:" + java.util.Base64.getEncoder().encodeToString(
                "synthetic-next-fingerprint-secret-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> messages(peer, env, rotatedKey, false));
        var nextService = messages(peer, env, rotatedKey + "," + FINGERPRINT_KEY, false);
        var retry = nextService.send(user, room, joined.membershipId(), "message-peer", client, "original", "masked");
        assertTrue(retry.deduplicated());
        assertEquals(first.message().messageId(), retry.message().messageId());
        assertEquals(1, jdbc.queryForObject("SELECT SUM(accepted_count) FROM chat_send_repetitions WHERE environment=?",
                Integer.class, env));
        assertEquals("CLIENT_ID_CONFLICT", assertThrows(ChatMessageRejection.class,
                () -> nextService.send(user, room, joined.membershipId(), "message-peer", client, "changed", "masked"))
                .reason());
        String second = UUID.randomUUID().toString();
        assertEquals("RATE_LIMIT", assertThrows(ChatMessageRejection.class,
                () -> nextService.send(user, room, joined.membershipId(), "message-peer", second, "other", "other"))
                .reason());
        jdbc.update("UPDATE chat_send_policy SET last_accepted_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE environment=?", env);
        nextService.send(user, room, joined.membershipId(), "message-peer", second, "original", "masked");
        jdbc.update("UPDATE chat_send_policy SET last_accepted_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE environment=?", env);
        assertEquals("DUPLICATE_CONTENT", assertThrows(ChatMessageRejection.class,
                () -> nextService.send(user, room, joined.membershipId(), "message-peer", UUID.randomUUID().toString(),
                        "original", "masked"))
                .reason());
        var otherMembership = peer.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(peer, user, otherMembership, "other-member");
        assertEquals("CLIENT_ID_CONFLICT", assertThrows(ChatMessageRejection.class,
                () -> nextService.send(user, room, otherMembership.membershipId(), "other-member", client,
                        "original", "masked"))
                .reason());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE environment=?",
                Integer.class, env));
    }

    @Test
    void concurrentSameUuidHasOneMessageReceiptAndOutboxAndRollbackHasNone() throws Exception {
        String env = "message-race";
        var one = service(env, snapshot -> {
        });
        var two = service(env, snapshot -> {
        });
        var writerOne = messages(one, env, FINGERPRINT_KEY, false);
        var writerTwo = messages(two, env, FINGERPRINT_KEY, false);
        long user = user();
        String sid = UUID.randomUUID().toString();
        var tabOne = one.join(user, sid, room, claims(user)).membership();
        var tabTwo = two.join(user, sid, room, claims(user)).membership();
        connect(one, user, tabOne, "race-one");
        connect(two, user, tabTwo, "race-two");
        assertEquals(tabOne.membershipId(), tabTwo.membershipId());
        String client = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                start.await();
                return writerOne.send(user, room, tabOne.membershipId(),
                        "race-one", client, "race", "race");
            });
            var b = executor.submit(() -> {
                start.await();
                return writerTwo.send(user, room, tabTwo.membershipId(),
                        "race-two", client, "race", "race");
            });
            start.countDown();
            var ra = a.get(5, TimeUnit.SECONDS);
            var rb = b.get(5, TimeUnit.SECONDS);
            assertEquals(ra.message().messageId(), rb.message().messageId());
            assertTrue(ra.deduplicated() != rb.deduplicated());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_receipts WHERE environment=?",
                Integer.class, env));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE environment=?",
                Integer.class, env));
        long failing = user();
        var failedTab = one.join(failing, UUID.randomUUID().toString(), room, claims(failing)).membership();
        connect(one, failing, failedTab, "rollback");
        var broken = messages(one, env, FINGERPRINT_KEY, true);
        assertThrows(ChatParticipationUnavailableException.class, () -> broken.send(failing, room,
                failedTab.membershipId(), "rollback", UUID.randomUUID().toString(), "rollback", "rollback"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE user_id=?", Integer.class,
                failing));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_send_policy WHERE user_id=?", Integer.class,
                failing));
    }

    @Test
    void outboxClaimsFenceLateResultsRetrySameEventAndExpireWithoutPublishing() {
        String env = "message-outbox";
        var one = service(env, snapshot -> {
        });
        var two = service(env, snapshot -> {
        });
        long user = user();
        var tab = one.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(one, user, tab, "outbox");
        var writer = messages(one, env, FINGERPRINT_KEY, false);
        var saved = writer.send(user, room, tab.membershipId(), "outbox", UUID.randomUUID().toString(), "pending",
                "safe");
        var outboxOne = new ChatMessageOutboxStore(jdbc, new DataSourceTransactionManager(dataSource), one, env);
        var outboxTwo = new ChatMessageOutboxStore(jdbc, new DataSourceTransactionManager(dataSource), two, env);
        var old = outboxOne.claim(room);
        assertTrue(old != null);
        assertEquals(null, outboxTwo.claim(room));
        jdbc.update("UPDATE chat_room_runtime SET dispatch_until=UTC_TIMESTAMP(6) WHERE environment=?", env);
        var replacement = outboxTwo.claim(room);
        assertEquals(old.event().eventId(), replacement.event().eventId());
        assertFalse(outboxOne.publishable(old));
        assertTrue(outboxTwo.publishable(replacement));
        assertFalse(outboxOne.complete(old, true));
        assertTrue(outboxTwo.complete(replacement, false));
        assertEquals(null, outboxOne.claim(room));
        jdbc.update("UPDATE chat_message_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE environment=?", env);
        var retry = outboxOne.claim(room);
        assertEquals(old.event().eventId(), retry.event().eventId());
        assertEquals(1, retry.attempts());
        String body = retry.event().encode();
        assertFalse(body.contains("safe"));
        assertFalse(body.contains("pending"));
        assertEquals(retry.event(),
                ChatMessageRedisEvent.decode(env + ":chat:room:" + room, env + ":chat:room:", body));
        assertThrows(IllegalArgumentException.class,
                () -> ChatMessageRedisEvent.decode(env + ":chat:room:999999", env + ":chat:room:", body));
        assertThrows(IllegalArgumentException.class,
                () -> ChatMessageRedisEvent.decode(env + ":chat:room:" + room, env + ":chat:room:",
                        body.replace("\"payload\":{", "\"unknown\":true,\"payload\":{")));
        assertTrue(outboxOne.complete(retry, true));
        assertEquals(null, outboxTwo.claim(room));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE id=?", Integer.class,
                saved.message().messageId()));
        jdbc.update("UPDATE chat_send_policy SET last_accepted_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE environment=?", env);
        writer.send(user, room, tab.membershipId(), "outbox", UUID.randomUUID().toString(), "expired", "safe");
        jdbc.update(
                "UPDATE chat_message_outbox SET expires_at=UTC_TIMESTAMP(6) WHERE environment=? AND status='PENDING'",
                env);
        assertEquals(null, outboxOne.claim(room));
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE environment=? AND status='EXPIRED'",
                        Integer.class, env));
    }

    @Test
    void outboxInsertFailureRollsBackMessageReceiptAndPolicyTogether() {
        String env = "message-atomic";
        var owner = service(env, snapshot -> {
        });
        long user = user();
        var tab = owner.join(user, UUID.randomUUID().toString(), room, claims(user)).membership();
        connect(owner, user, tab, "atomic");
        var writer = messages(owner, env, FINGERPRINT_KEY, false);
        String client = UUID.randomUUID().toString();
        jdbc.execute("""
                CREATE TRIGGER fixture_outbox_failure BEFORE INSERT ON chat_message_outbox FOR EACH ROW
                BEGIN IF NEW.environment='message-atomic' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic outbox rollback'; END IF; END
                """);
        try {
            assertThrows(ChatParticipationUnavailableException.class, () -> writer.send(user, room,
                    tab.membershipId(), "atomic", client, "original", "masked"));
        } finally {
            jdbc.execute("DROP TRIGGER fixture_outbox_failure");
        }
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE user_id=?", Integer.class, user));
        for (String table : List.of("chat_message_receipts", "chat_send_policy", "chat_send_repetitions",
                "chat_message_outbox")) {
            assertEquals(0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE environment=?", Integer.class, env));
        }
        assertFalse(writer.send(user, room, tab.membershipId(), "atomic", client, "original", "masked").deduplicated());
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE environment=? AND status='PENDING'",
                        Integer.class, env));
    }

    @Test
    void realRedisReferenceEventsDeduplicateReorderingAndExcludeNewSubscriptionGaps() throws Exception {
        String env = "message-events";
        var owner = service(env, snapshot -> {
        });
        var peer = service(env, snapshot -> {
        });
        long sender = user();
        long reader = user();
        var writing = owner.join(sender, UUID.randomUUID().toString(), room, claims(sender)).membership();
        var reading = peer.join(reader, UUID.randomUUID().toString(), room, claims(reader)).membership();
        connect(owner, sender, writing, "writer");
        connect(peer, reader, reading, "reader");
        var writer = messages(owner, env, FINGERPRINT_KEY, false);
        var first = writer.send(sender, room, writing.membershipId(), "writer", UUID.randomUUID().toString(), "first",
                "masked1");
        jdbc.update(
                "UPDATE chat_send_policy SET last_accepted_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                        + "WHERE environment=?",
                env);
        var second = writer.send(sender, room, writing.membershipId(), "writer", UUID.randomUUID().toString(), "second",
                "masked2");
        var properties = new ChatRedisProperties(env, REDIS.getHost(), REDIS.getMappedPort(6379), "default",
                "isolated-redis-password", false, Duration.ofSeconds(1), Duration.ofSeconds(1));
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.setParticipation(peer);
        presence.connect(reader, room, reading.membershipId(), 1000, "reader");
        presence.attachTab(reading.membershipId(), reading.viewSessionId(), "reader");
        presence.subscribed("reader", "room", "/topic/chat-rooms/" + room);
        var registry = new ChatAuthSessionRegistry();
        registry.register("reader", new ChatAuthSessionRegistry.Context(reader, membershipSid(reading.membershipId())));
        var template = mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        var delivered = new java.util.concurrent.LinkedBlockingQueue<Long>();
        org.mockito.Mockito.doAnswer(call -> {
            ChatMessageEvent event = call.getArgument(1);
            delivered.add(((ChatMessageResponse) event.data()).messageId());
            return null;
        }).when(template).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/chat-rooms/" + room),
                org.mockito.ArgumentMatchers.any(Object.class), org.mockito.ArgumentMatchers.anyMap());
        var beanFactory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beanFactory.registerSingleton("broker", template);
        var channels = new ChatRedisChannels(properties);
        var store = new ChatMessageOutboxStore(jdbc, new DataSourceTransactionManager(dataSource), peer, env);
        try (var connection = new ChatRedisConnection(properties);
                var relay = new ChatMessageRelay(jdbc, peer, store, messages(peer, env, FINGERPRINT_KEY, false),
                        connection, channels, presence, registry, mock(ChatSessionVerifier.class),
                        beanFactory.getBeanProvider(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                        env)) {
            awaitCondition(relay::ready);
            var e1 = event(env, first.message().messageId());
            var e2 = event(env, second.message().messageId());
            connection.commands().sync().publish(channels.roomChannel(room), e2.encode());
            connection.commands().sync().publish(channels.roomChannel(room), e1.encode());
            connection.commands().sync().publish(channels.roomChannel(room), e1.encode());
            assertEquals(Set.of(first.message().messageId(), second.message().messageId()),
                    Set.of(delivered.poll(3, TimeUnit.SECONDS), delivered.poll(3, TimeUnit.SECONDS)));
            assertEquals(null, delivered.poll(300, TimeUnit.MILLISECONDS));
            var forged = new ChatMessageRedisEvent(UUID.randomUUID().toString(), "chat:" + UUID.randomUUID(),
                    e1.epoch(), room, e1.roomSeq(), 9007199254740990L, e1.occurredAt());
            connection.commands().sync().publish(channels.roomChannel(room), forged.encode());
            connection.commands().sync().publish(channels.roomChannel(room), e1.encode() + "{}");
            connection.commands().sync().publish(channels.roomChannel(room), new ChatMessageRedisEvent(
                    UUID.randomUUID().toString(), e1.producerId(), e1.epoch() + 1, room, e1.roomSeq(),
                    e1.messageId(), e1.occurredAt()).encode());
            awaitCondition(() -> relay.rejectedEvents() >= 3);
            assertEquals(null, delivered.poll(100, TimeUnit.MILLISECONDS));
            peer.paused(reader, room, reading.membershipId(), reading.viewSessionId(), "reader");
            jdbc.update(
                    "UPDATE chat_send_policy SET last_accepted_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                            + "WHERE environment=?",
                    env);
            var hidden = writer.send(sender, room, writing.membershipId(), "writer", UUID.randomUUID().toString(),
                    "absent", "masked3");
            peer.ready(reader, room, reading.membershipId(), reading.viewSessionId(), "reader");
            connection.commands().sync().publish(channels.roomChannel(room),
                    event(env, hidden.message().messageId()).encode());
            assertEquals(null, delivered.poll(300, TimeUnit.MILLISECONDS));
            assertTrue(relay.ready());
        }
    }

    @Test
    void distributedRetentionUsesDbTimeEvenWhenTheTaskClockIsTwoDaysAhead() {
        long user = user();
        var valid = insert(user);
        var expired = insert(user);
        jdbc.update("UPDATE chat_room_messages SET created_at=TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6)) WHERE id=?",
                expired.messageId());
        var repository = mock(com.muse.meomuneum.chat.message.repository.ChatRoomMessageRepository.class);
        when(repository.deleteExpired(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenAnswer(call -> jdbc.update("DELETE FROM chat_room_messages WHERE created_at<=?",
                        (LocalDateTime) call.getArgument(0)));
        var storage = new ChatMessageStorageService(repository, mock(UserRepository.class),
                mock(com.muse.meomuneum.chat.room.repository.ChatRoomRepository.class),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(48)));
        storage.setDatabaseClock(jdbc, true);
        storage.deleteExpiredMessages();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE id=?", Integer.class,
                valid.messageId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE id=?", Integer.class,
                expired.messageId()));
    }

    private ChatMessageRedisEvent event(String env, long message) {
        var row = jdbc.queryForMap("SELECT * FROM chat_message_outbox WHERE environment=? AND message_id=?", env,
                message);
        Object at = row.get("occurred_at");
        var time = at instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) at;
        return new ChatMessageRedisEvent((String) row.get("event_id"), (String) row.get("producer_id"),
                ((Number) row.get("epoch")).longValue(), ((Number) row.get("room_id")).longValue(),
                ((Number) row.get("room_seq")).longValue(), message, time.toInstant(ZoneOffset.UTC));
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private DistributedChatMessageService messages(ChatParticipationService participation, String environment,
            String keys, boolean failAfterInsert) {
        var storage = mock(ChatMessageStorageService.class);
        when(storage.store(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
                    long user = call.getArgument(0);
                    long messageRoom = call.getArgument(1);
                    String client = call.getArgument(2);
                    String content = call.getArgument(3);
                    jdbc.update("""
                            INSERT INTO chat_room_messages(room_id,user_id,client_message_id,content,created_at)
                            VALUES (?,?,?,?,UTC_TIMESTAMP(6))
                            """, messageRoom, user, client, content);
                    if (failAfterInsert) {
                        throw new IllegalStateException("synthetic rollback");
                    }
                    long id = jdbc.queryForObject(
                            "SELECT id FROM chat_room_messages WHERE user_id=? AND client_message_id=?",
                            Long.class, user, client);
                    var account = mock(User.class);
                    when(account.getId()).thenReturn(user);
                    when(account.getNickname()).thenReturn("fixture");
                    var target = mock(ChatRoom.class);
                    when(target.getId()).thenReturn(messageRoom);
                    var message = ChatRoomMessage.create(account, target, client, content,
                            LocalDateTime.now(ZoneOffset.UTC));
                    ReflectionTestUtils.setField(message, "id", id);
                    return new ChatMessageSaveResult(message, false);
                });
        return new DistributedChatMessageService(jdbc, participation, storage, new ChatMessageFingerprint(keys),
                environment);
    }

    private ChatCursorCodec codec() {
        return new ChatCursorCodec(
                new ObjectMapper(),
                Clock.systemUTC(), "synthetic-cursor-key-at-least-thirty-two-bytes");
    }

    private DistributedChatMessageQueryService queries(
            ChatParticipationService service) {
        return new DistributedChatMessageQueryService(service,
                new NamedParameterJdbcTemplate(jdbc), codec());
    }

    private void connect(ChatParticipationService service, long user,
            ChatRoomMembershipResponse membership, String socket) {
        service.claim(user, membershipSid(membership.membershipId()), room, membership.membershipId(),
                membership.viewSessionId(), socket);
        service.ready(user, room, membership.membershipId(), membership.viewSessionId(), socket);
    }

    private ChatMessageResponse send(ChatParticipationService service,
            long user, long member, String socket) {
        return service.writeMessage(user, room, member, socket, () -> insert(user));
    }

    private ChatMessageResponse insert(long user) {
        String client = UUID.randomUUID().toString();
        jdbc.update(
                """
                        INSERT INTO chat_room_messages(room_id,user_id,client_message_id,content,created_at)
                        VALUES (?,?,?,'fixture',UTC_TIMESTAMP(6))
                        """,
                room, user, client);
        long id = jdbc.queryForObject("SELECT id FROM chat_room_messages WHERE user_id=? AND client_message_id=?",
                Long.class, user, client);
        var time = jdbc.queryForObject("SELECT created_at FROM chat_room_messages WHERE id=?",
                LocalDateTime.class, id);
        return new ChatMessageResponse(id, client, room, user, "fixture", "fixture",
                time.atOffset(ZoneOffset.UTC));
    }

    private ChatParticipationService service(String environment, ChatParticipationProjection projection) {
        var users = mock(UserRepository.class);
        when(users.findActiveByIdForUpdate(anyLong())).thenReturn(Optional.of(mock(User.class)));
        var members = mock(ChatRoomMemberRepository.class);
        when(members.findByIdAndUser_IdAndChatRoom_Id(anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            var rows = jdbc.queryForList("SELECT * FROM chat_room_members WHERE id=? AND deleted_at IS NULL", id);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            ChatRoomMember member = mock(ChatRoomMember.class);
            ChatRoom chatRoom = mock(ChatRoom.class);
            when(chatRoom.isActive()).thenReturn(true);
            when(member.isActive()).thenReturn(true);
            when(member.getChatRoom()).thenReturn(chatRoom);
            when(member.getAuthSessionId()).thenReturn((String) rows.getFirst().get("auth_session_id"));
            return Optional.of(member);
        });
        var storage = new ChatViewStorageService(jdbc, members, users, mock(ChatBanStorageService.class),
                Clock.systemUTC());
        ReflectionTestUtils.setField(storage, "distributedPresence", true);
        var service = new ChatParticipationService(jdbc, new DataSourceTransactionManager(dataSource), storage,
                mock(ChatSessionAccessPolicy.class), projection, event -> {
                }, new ChatSocketSessions(), environment);
        service.start();
        return service;
    }

    private long user() {
        String email = UUID.randomUUID().toString().substring(0, 16) + "@x.test";
        jdbc.update("INSERT INTO users(email,password_hash,nickname,role) VALUES (?,'synthetic',?,'USER')",
                email, "p" + UUID.randomUUID().toString().substring(0, 8));
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private LocationResolutionClaims claims(long user) {
        return new LocationResolutionClaims(user, 41L, "41", region, "41135", Instant.now().plusSeconds(60));
    }

    private String membershipSid(long member) {
        return jdbc.queryForObject("SELECT auth_session_id FROM chat_room_members WHERE id=?", String.class, member);
    }

    private String state(String view) {
        return jdbc.queryForObject("SELECT state FROM chat_view_runtime WHERE view_id=?", String.class, view);
    }
}
