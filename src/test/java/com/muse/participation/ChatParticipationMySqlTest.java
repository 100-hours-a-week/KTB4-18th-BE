package com.muse.participation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.participation.ChatParticipationProjection;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;
import com.muse.meomuneum.chat.participation.RedisChatParticipationProjection;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Testcontainers
class ChatParticipationMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("participation").withUsername("test").withPassword("isolated-test-password");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "isolated-redis-password");
    private static JdbcTemplate jdbc;
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static long room;
    private static long region;

    @BeforeAll
    static void migrate() {
        Flyway old = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("20261020213622").load();
        old.migrate();
        dataSource = new com.zaxxer.hikari.HikariDataSource();
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

    @org.junit.jupiter.api.AfterAll
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
            var attempts = java.util.stream.IntStream.range(0, 8).mapToObj(i -> {
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
                "default", "isolated-redis-password", false, java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1), 1024, "");
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
