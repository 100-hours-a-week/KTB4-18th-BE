package com.muse.meomuneum.chat.room.service;

import static com.muse.meomuneum.chat.support.ChatTestSessions.sid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.chat.region.repository.RegionRepository;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;
import com.muse.meomuneum.location.security.IssuedLocationToken;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.message-cleanup.enabled=false", "recommendation.ai.base-url=http://localhost:8000",
        "speech.transcription.ai.base-url=http://localhost:8000",
        "location.reverse-geocoding.base-url=http://localhost:8000"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ChatRoomEntryIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("meomuneum_chat_entry_test").withUsername("meomuneum_test")
            .withPassword("meomuneum_test");

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher events;

    @Autowired
    private com.muse.meomuneum.global.security.JwtTokenProvider jwt;

    @Autowired
    private com.muse.meomuneum.user.repository.UserRepository users;

    @Autowired
    private ChatRoomEntryService service;

    @Autowired
    private com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry authSessions;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.muse.meomuneum.location.provider.RegionCoordinateResolver coordinates;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository memberRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private LocationResolutionTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void resetEntryState() {
        jdbcTemplate.queryForList("SELECT id FROM users WHERE email LIKE 'chat-entry-%'", Long.class)
                .forEach(service::leaveAll);
        jdbcTemplate.update("DELETE FROM chat_room_messages");
        jdbcTemplate.update("DELETE FROM chat_bans");
        jdbcTemplate.update("DELETE FROM chat_view_sessions");
        jdbcTemplate.update("DELETE FROM chat_room_members");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'chat-entry-%'");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 25, status = 'ACTIVE'");
    }

    @Test
    void realSocketsKeepSignedSessionAcrossTokenRotationAndRemoveOnlyClosedConnection() throws Exception {
        Long userId = createUser("auth-context");
        ChatRoom room = roomFor("41135");
        var joined = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion())).membership();
        String sid = sid(userId);
        String otherSid = java.util.UUID.randomUUID().toString();
        var user = users.findById(userId).orElseThrow();
        var first = connectSocket(userId, room.getId(), joined.membershipId(), jwt.createAccessToken(user, sid));
        var refreshed = connectSocket(userId, room.getId(), joined.membershipId(), jwt.createAccessToken(user, sid));
        Long otherMembership = service
                .join(userId, otherSid, room.getId(), issueToken(userId, room.getRegion(), otherSid))
                .membership().membershipId();
        var other = connectSocket(userId, room.getId(), otherMembership, jwt.createAccessToken(user, otherSid));
        try {
            assertEquals(2, authSessions.connections(userId, sid).size());
            assertEquals(1, authSessions.connections(userId, otherSid).size());
            first.socket.sendClose(1000, "test complete").join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (authSessions.connections(userId, sid).size() != 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, authSessions.connections(userId, sid).size());
            assertEquals(1, authSessions.connections(userId, otherSid).size());
        } finally {
            first.socket.abort();
            refreshed.socket.abort();
            other.socket.abort();
        }
    }

    @Test
    void repeatedConcurrentJoinKeepsOneActiveMembership() throws Exception {
        Long userId = createUser("same-user");
        ChatRoom room = roomFor("41135");
        String token = issueToken(userId, room.getRegion());

        List<ChatRoomJoinResult> results = runConcurrently(() -> service.join(userId, sid(userId), room.getId(), token),
                () -> service.join(userId, sid(userId), room.getId(), token));

        assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(room.getId()));
        assertEquals(results.get(0).membership().membershipId(), results.get(1).membership().membershipId());
        assertEquals(1L, results.stream().filter(ChatRoomJoinResult::created).count());
    }

    @Test
    void concurrentJoinDoesNotExceedRoomCapacity() throws Exception {
        Long firstUserId = createUser("capacity-first");
        Long secondUserId = createUser("capacity-second");
        ChatRoom room = roomFor("41135");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 1 WHERE id = ?", room.getId());
        String firstToken = issueToken(firstUserId, room.getRegion());
        String secondToken = issueToken(secondUserId, room.getRegion());

        List<String> outcomes = runConcurrently(() -> joinOutcome(firstUserId, room.getId(), firstToken),
                () -> joinOutcome(secondUserId, room.getId(), secondToken));

        assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(room.getId()));
        assertEquals(1L, outcomes.stream().filter("joined"::equals).count());
        assertEquals(1L, outcomes.stream().filter("full"::equals).count());
    }

    @Test
    void capacityCheckReadsLatestCommittedMembershipAfterEarlierSnapshot() throws Exception {
        Long firstUserId = createUser("snapshot-first");
        Long secondUserId = createUser("snapshot-second");
        ChatRoom room = roomFor("41135");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 1 WHERE id = ?", room.getId());
        String firstToken = issueToken(firstUserId, room.getRegion());
        String secondToken = issueToken(secondUserId, room.getRegion());
        CountDownLatch snapshotCreated = new CountDownLatch(1);
        CountDownLatch firstJoinCompleted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<String> secondOutcome = executor
                    .submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                        assertEquals(0L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(room.getId()));
                        snapshotCreated.countDown();
                        await(firstJoinCompleted);
                        return joinOutcome(secondUserId, room.getId(), secondToken);
                    }));

            assertTrue(snapshotCreated.await(5, TimeUnit.SECONDS));
            service.join(firstUserId, sid(firstUserId), room.getId(), firstToken);
            firstJoinCompleted.countDown();

            assertEquals("full", secondOutcome.get(5, TimeUnit.SECONDS));
            assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(room.getId()));
        } finally {
            firstJoinCompleted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentMovesLockBothRoomsInAStableOrder() throws Exception {
        Long firstUserId = createUser("swap-first");
        Long secondUserId = createUser("swap-second");
        ChatRoom firstRoom = roomFor("41135");
        ChatRoom secondRoom = roomFor("11680");
        service.join(firstUserId, sid(firstUserId), firstRoom.getId(), issueToken(firstUserId, firstRoom.getRegion()));
        service.join(secondUserId, sid(secondUserId), secondRoom.getId(),
                issueToken(secondUserId, secondRoom.getRegion()));

        List<ChatRoomJoinResult> results = runConcurrently(
                () -> service.join(firstUserId, sid(firstUserId), secondRoom.getId(),
                        issueToken(firstUserId, secondRoom.getRegion())),
                () -> service.join(secondUserId, sid(secondUserId), firstRoom.getId(),
                        issueToken(secondUserId, firstRoom.getRegion())));

        assertEquals(2, results.size());
        assertEquals(secondRoom.getId(),
                memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(firstUserId, sid(firstUserId))
                        .orElseThrow().getChatRoom().getId());
        assertEquals(firstRoom.getId(),
                memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(secondUserId, sid(secondUserId))
                        .orElseThrow().getChatRoom().getId());
    }

    @Test
    void fullDestinationRoomDoesNotRestoreThePreviousMembership() {
        Long movingUserId = createUser("moving-user");
        Long occupyingUserId = createUser("occupying-user");
        ChatRoom previousRoom = roomFor("41135");
        ChatRoom fullRoom = roomFor("11680");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 1 WHERE id = ?", fullRoom.getId());

        service.join(movingUserId, sid(movingUserId), previousRoom.getId(),
                issueToken(movingUserId, previousRoom.getRegion()));
        service.join(occupyingUserId, sid(occupyingUserId), fullRoom.getId(),
                issueToken(occupyingUserId, fullRoom.getRegion()));

        ChatRoomException exception = org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.join(movingUserId, sid(movingUserId), fullRoom.getId(),
                        issueToken(movingUserId, fullRoom.getRegion())));

        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, exception.getErrorCode());
        assertFalse(memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(movingUserId, sid(movingUserId))
                .isPresent());
        assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(fullRoom.getId()));
    }

    @Test
    void rejectsAValidTokenForAnotherRegion() {
        Long userId = createUser("wrong-region");
        ChatRoom requestedRoom = roomFor("41135");
        ChatRoom actualRoom = roomFor("11680");

        ChatRoomException exception = org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.join(userId, sid(userId), requestedRoom.getId(),
                        issueToken(userId, actualRoom.getRegion())));

        assertEquals(ChatRoomErrorCode.LOCATION_REGION_MISMATCH, exception.getErrorCode());
        assertTrue(memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(userId, sid(userId)).isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void twentySixSimultaneousJoinsAdmitExactlyTwentyFiveUsers() throws Exception {
        ChatRoom room = roomFor("41135");
        List<Callable<String>> tasks = new java.util.ArrayList<>();
        for (int index = 0; index < 26; index++) {
            Long userId = createUser("many-" + index);
            String token = issueToken(userId, room.getRegion());
            tasks.add(() -> joinOutcome(userId, room.getId(), token));
        }
        List<String> outcomes = runConcurrently(tasks.toArray(Callable[]::new));
        assertEquals(25L, outcomes.stream().filter("joined"::equals).count());
        assertEquals(1L, outcomes.stream().filter("full"::equals).count());
    }

    @Test
    void staleLeaveDoesNotEndANewerMembershipAndAnotherUserCannotLeaveIt() {
        Long userId = createUser("leave-owner");
        Long otherId = createUser("leave-other");
        ChatRoom room = roomFor("41135");
        String token = issueToken(userId, room.getRegion());
        Long previous = service.join(userId, sid(userId), room.getId(), token).membership().membershipId();
        service.leave(userId, sid(userId), room.getId(), previous);
        Long current = service.join(userId, sid(userId), room.getId(), token).membership().membershipId();
        service.leave(userId, sid(userId), room.getId(), previous);
        assertEquals(current, memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(userId, sid(userId))
                .orElseThrow().getId());
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.leave(otherId, sid(otherId), room.getId(), current));
    }

    @Test
    void bannedUsersCannotJoinConnectOrSubscribe() {
        Long userId = createUser("banned");
        ChatRoom room = roomFor("41135");
        String token = issueToken(userId, room.getRegion());
        Long membershipId = service.join(userId, sid(userId), room.getId(), token).membership().membershipId();
        service.connect(userId, sid(userId), room.getId(), membershipId, "session");
        jdbcTemplate.update("""
                INSERT INTO chat_bans (user_id, reason, created_at, expires_at)
                VALUES (?, 'PROFANITY', UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY))
                """, userId);
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.join(userId, sid(userId), room.getId(), token));
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.connect(userId, sid(userId), room.getId(), membershipId, "second"));
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.authorizeConnection(userId, room.getId(), membershipId, "session"));
    }

    @Test
    void browserStompAuthenticatesReceivesSubscriptionReceiptAndAccountWideLeaveClosesBothSockets()
            throws Exception {
        Long userId = createUser("websocket");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(userId, room.getId(), membershipId);
        SocketProbe second = connectSocket(userId, room.getId(), membershipId);
        try {
            first.socket.sendText("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + room.getId()
                    + "\nreceipt:ready\n\n\u0000", true).join();
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("receipt-id:ready"));
            assertTrue(first.viewFrames.poll(5, TimeUnit.SECONDS).contains("view_session_id"));
            assertEquals(null, second.viewFrames.poll(150, TimeUnit.MILLISECONDS));
            service.leave(userId, sid(userId), room.getId(), membershipId);
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                    () -> service.connect(userId, sid(userId), room.getId(), membershipId, "late"));
        } finally {
            first.socket.abort();
            second.socket.abort();
        }
    }

    @Test
    void browserStompRejectsSubscriptionsToAnotherRoom() throws Exception {
        Long userId = createUser("cross-room");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership().membershipId();
        SocketProbe probe = connectSocket(userId, room.getId(), membershipId);
        try {
            probe.socket.sendText("SUBSCRIBE\nid:other\ndestination:/topic/chat-rooms/999999\n\n\u0000", true).join();
            assertTrue(probe.frames.poll(5, TimeUnit.SECONDS).startsWith("ERROR"));
        } finally {
            probe.socket.abort();
        }
    }

    @Test
    void logoutEndsOnlyTheAuthenticatedSessionsConnections() throws Exception {
        Long userId = createUser("logout");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(userId, room.getId(), membershipId);
        SocketProbe second = connectSocket(userId, room.getId(), membershipId);
        try {
            events.publishEvent(new com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent(userId, sid(userId)));
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
            assertTrue(memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(userId, sid(userId)).isEmpty());
        } finally {
            first.socket.abort();
            second.socket.abort();
        }
    }

    @Test
    void sessionLogoutKeepsOtherDeviceMessagingAndRejectsOldSessionAdmission() throws Exception {
        Long userId = createUser("logout-isolation");
        ChatRoom room = roomFor("41135");
        String otherSid = java.util.UUID.randomUUID().toString();
        var first = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership();
        var second = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership();
        var other = service.join(userId, otherSid, room.getId(), issueToken(userId, room.getRegion(), otherSid))
                .membership();
        String token = jwt.createAccessToken(users.findById(userId).orElseThrow(), sid(userId));
        String otherToken = jwt.createAccessToken(users.findById(userId).orElseThrow(), otherSid);
        SocketProbe a = connectSocket(userId, room.getId(), first.membershipId(), token, first.viewSessionId());
        SocketProbe b = connectSocket(userId, room.getId(), second.membershipId(), token, second.viewSessionId());
        SocketProbe device = connectSocket(userId, room.getId(), other.membershipId(), otherToken,
                other.viewSessionId());
        try {
            subscribeMessages(device, room.getId());
            var event = new com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent(userId, sid(userId));
            events.publishEvent(event);
            assertEquals(4100, a.closed.get(5, TimeUnit.SECONDS));
            assertEquals("CHAT_LOGGED_OUT", a.closeReason);
            assertEquals(4100, b.closed.get(5, TimeUnit.SECONDS));
            assertFalse(device.closed.isDone());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_view_sessions WHERE membership_id = ? AND ended_at IS NULL",
                    Integer.class, first.membershipId()));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_view_sessions WHERE membership_id = ? AND ended_at IS NULL",
                    Integer.class, other.membershipId()));
            assertTrue(memberRepository.findById(other.membershipId()).orElseThrow().isActive());
            assertEquals(ChatRoomErrorCode.AUTH_REQUIRED, org.junit.jupiter.api.Assertions.assertThrows(
                    ChatRoomException.class, () -> service.join(userId, sid(userId), room.getId(),
                            issueToken(userId, room.getRegion())))
                    .getErrorCode());
            events.publishEvent(event);
            assertFalse(device.closed.isDone());
            sendMessage(device, room.getId(), java.util.UUID.randomUUID().toString(), "other device after logout");
            assertTrue(nextFrame(device).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(device).contains("CHAT_ACK"));
            assertEquals(other.membershipId(), service.join(userId, otherSid, room.getId(),
                    issueToken(userId, room.getRegion(), otherSid)).membership().membershipId());
        } finally {
            service.leaveAll(userId);
            a.socket.abort();
            b.socket.abort();
            device.socket.abort();
        }
    }

    @Test
    void logoutReleasesUnconnectedReservationsAndDoesNotAffectANewLogin() {
        Long userId = createUser("logout-reserve");
        ChatRoom room = roomFor("41135");
        var joined = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership();
        events.publishEvent(new com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent(userId, sid(userId)));
        assertFalse(memberRepository.findById(joined.membershipId()).orElseThrow().isActive());
        String newSid = java.util.UUID.randomUUID().toString();
        var newLogin = service.join(userId, newSid, room.getId(), issueToken(userId, room.getRegion(), newSid))
                .membership();
        events.publishEvent(new com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent(userId, sid(userId)));
        assertTrue(memberRepository.findById(newLogin.membershipId()).orElseThrow().isActive());
        assertEquals(newLogin.membershipId(), service.join(userId, newSid, room.getId(),
                issueToken(userId, room.getRegion(), newSid)).membership().membershipId());
        service.leaveAll(userId);
    }

    @Test
    void textDeliveryAcknowledgesOnceAndNeverReplaysToLaterOrOtherRoomSubscribers() throws Exception {
        Long firstId = createUser("message-first");
        Long secondId = createUser("message-second");
        Long thirdId = createUser("message-third");
        ChatRoom room = roomFor("41135");
        ChatRoom otherRoom = roomFor("11680");
        Long firstMembership = service.join(firstId, sid(firstId), room.getId(), issueToken(firstId, room.getRegion()))
                .membership().membershipId();
        Long secondMembership = service
                .join(secondId, sid(secondId), room.getId(), issueToken(secondId, room.getRegion()))
                .membership().membershipId();
        Long thirdMembership = service
                .join(thirdId, sid(thirdId), otherRoom.getId(), issueToken(thirdId, otherRoom.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(firstId, room.getId(), firstMembership);
        SocketProbe second = connectSocket(secondId, room.getId(), secondMembership);
        SocketProbe third = connectSocket(thirdId, otherRoom.getId(), thirdMembership);
        try {
            subscribeMessages(first, room.getId());
            subscribeMessages(third, otherRoom.getId());
            String id = java.util.UUID.randomUUID().toString();
            sendMessage(first, room.getId(), id, "hello");
            assertTrue(nextFrame(first).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(first).contains("CHAT_ACK"));
            assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Long.class));
            assertEquals(null, second.frames.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(null, third.frames.poll(150, TimeUnit.MILLISECONDS));
            subscribeMessages(second, room.getId());
            assertEquals(null, second.frames.poll(150, TimeUnit.MILLISECONDS));
            sendMessage(first, room.getId(), id, "hello");
            assertTrue(nextFrame(first).contains("CHAT_ACK"));
            assertEquals(null, second.frames.poll(150, TimeUnit.MILLISECONDS));
            Thread.sleep(1050);
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "next");
            assertTrue(nextFrame(first).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(first).contains("CHAT_ACK"));
            String received = nextFrame(second);
            assertTrue(received.contains("CHAT_MESSAGE"));
            assertTrue(received.contains("message-firs"));
            assertTrue(received.contains("created_at"));
            assertEquals(null, third.frames.poll(150, TimeUnit.MILLISECONDS));
        } finally {
            first.socket.abort();
            second.socket.abort();
            third.socket.abort();
        }
    }

    @Test
    void normalRejectionsDoNotStoreBroadcastOrDisconnectAndMembershipCountsSurviveReconnect() throws Exception {
        Long userId = createUser("message-block");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, sid(userId), room.getId(), issueToken(userId, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(userId, room.getId(), membershipId);
        try {
            subscribeMessages(first, room.getId());
            for (String text : List.of("", "x".repeat(301), "https://example.com", "010-0000-0000")) {
                sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), text);
                assertTrue(nextFrame(first).contains("CHAT_REJECTED"));
            }
            String firstId = java.util.UUID.randomUUID().toString();
            sendMessage(first, room.getId(), firstId, "repeat");
            assertTrue(nextFrame(first).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(first).contains("CHAT_ACK"));
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "fast");
            assertTrue(nextFrame(first).contains("RATE_LIMIT"));
            Thread.sleep(1050);
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "repeat");
            assertTrue(nextFrame(first).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(first).contains("CHAT_ACK"));
            first.socket.abort();
            SocketProbe reconnect = connectSocket(userId, room.getId(), membershipId);
            try {
                subscribeMessages(reconnect, room.getId());
                Thread.sleep(1050);
                sendMessage(reconnect, room.getId(), java.util.UUID.randomUUID().toString(), "repeat");
                assertTrue(nextFrame(reconnect).contains("DUPLICATE_CONTENT"));
                sendMessage(reconnect, room.getId(), firstId, "changed");
                assertTrue(nextFrame(reconnect).contains("CLIENT_ID_CONFLICT"));
                assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Long.class));
                assertFalse(reconnect.closed.isDone());
            } finally {
                reconnect.socket.abort();
            }
        } finally {
            first.socket.abort();
        }
    }

    @Test
    void profanityMasksStorageBroadcastAndAckWithoutBanningOrClosingAndRetriesOnlyAck() throws Exception {
        Long author = createUser("mask");
        Long viewer = createUser("mask-watch");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(author, sid(author), room.getId(), issueToken(author, room.getRegion()))
                .membership().membershipId();
        Long viewerMembership = service.join(viewer, sid(viewer), room.getId(), issueToken(viewer, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(author, room.getId(), membershipId);
        SocketProbe second = connectSocket(author, room.getId(), membershipId);
        SocketProbe observer = connectSocket(viewer, room.getId(), viewerMembership);
        try {
            subscribeMessages(first, room.getId());
            subscribeMessages(second, room.getId());
            subscribeMessages(observer, room.getId());
            String clientId = java.util.UUID.randomUUID().toString();
            sendMessage(first, room.getId(), clientId, "가사에 씨발이 있어 😀");
            String firstFrame = first.frames.poll(5, TimeUnit.SECONDS);
            String nextFrame = first.frames.poll(5, TimeUnit.SECONDS);
            assertTrue(firstFrame != null && nextFrame != null);
            assertTrue(firstFrame.contains("가사에 **이 있어 😀"));
            assertTrue(nextFrame.contains("가사에 **이 있어 😀"));
            assertTrue(firstFrame.contains("CHAT_ACK") || nextFrame.contains("CHAT_ACK"));
            assertTrue(second.frames.poll(5, TimeUnit.SECONDS).contains("가사에 **이 있어 😀"));
            assertTrue(observer.frames.poll(5, TimeUnit.SECONDS).contains("가사에 **이 있어 😀"));
            assertEquals("가사에 **이 있어 😀", jdbcTemplate.queryForObject(
                    "SELECT content FROM chat_room_messages", String.class));
            assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_bans", Long.class));
            assertFalse(first.closed.isDone());
            assertFalse(second.closed.isDone());
            assertTrue(
                    memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(author, sid(author)).isPresent());
            // Same original UUID/content retries return stored masked ACK, without a second broadcast.
            sendMessage(first, room.getId(), clientId, "가사에 씨발이 있어 😀");
            String retry = first.frames.poll(5, TimeUnit.SECONDS);
            assertTrue(retry.contains("CHAT_ACK") && retry.contains("가사에 **이 있어 😀"));
            assertEquals(null, observer.frames.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Long.class));
            // A different original with the same masking result must not reuse the UUID.
            sendMessage(first, room.getId(), clientId, "가사에 씨바이 있어 😀");
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("CLIENT_ID_CONFLICT"));
            Thread.sleep(1050);
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "씨발 https://example.com");
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("URL_NOT_ALLOWED"));
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "씨발 010-0000-0000");
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("PERSONAL_INFORMATION"));
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "자지 빨아");
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("*****"));
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("*****"));
            assertTrue(observer.frames.poll(5, TimeUnit.SECONDS).contains("*****"));
            assertEquals("*****", jdbcTemplate.queryForObject(
                    "SELECT content FROM chat_room_messages ORDER BY id DESC LIMIT 1", String.class));
            assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_bans", Long.class));
        } finally {
            first.socket.abort();
            second.socket.abort();
            observer.socket.abort();
        }
    }

    @Test
    void presenceSnapshotChangesDeduplicateConnectionsAndExcludeGraceAndOtherRooms() throws Exception {
        Long owner = createUser("presence-one");
        Long other = createUser("presence-two");
        Long outsider = createUser("presence-other");
        ChatRoom room = roomFor("41135");
        ChatRoom otherRoom = roomFor("11680");
        Long ownMembership = service.join(owner, sid(owner), room.getId(), issueToken(owner, room.getRegion()))
                .membership().membershipId();
        Long otherMembership = service.join(other, sid(other), room.getId(), issueToken(other, room.getRegion()))
                .membership().membershipId();
        Long outsiderMembership = service
                .join(outsider, sid(outsider), otherRoom.getId(), issueToken(outsider, otherRoom.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(owner, room.getId(), ownMembership);
        SocketProbe second = null;
        SocketProbe device = null;
        SocketProbe outside = null;
        try {
            subscribeMessages(first, room.getId());
            assertPresence(first, 1);
            second = connectSocket(other, room.getId(), otherMembership);
            assertPresence(first, 2);
            subscribeMessages(second, room.getId());
            assertPresence(second, 2);
            device = connectSocket(other, room.getId(), otherMembership);
            assertEquals(null, first.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            second.socket.abort();
            assertEquals(null, first.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            device.socket.abort();
            assertPresence(first, 1);
            device = connectSocket(other, room.getId(), otherMembership);
            assertPresence(first, 2);
            subscribeMessages(device, room.getId());
            assertPresence(device, 2);
            outside = connectSocket(outsider, otherRoom.getId(), outsiderMembership);
            subscribeMessages(outside, otherRoom.getId());
            assertPresence(outside, 1);
            assertEquals(null, first.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            service.leave(other, sid(other), room.getId(), otherMembership);
            assertPresence(first, 1);
            assertEquals(4100, device.closed.get(5, TimeUnit.SECONDS));
            assertEquals(null, outside.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
        } finally {
            first.socket.abort();
            if (second != null) {
                second.socket.abort();
            }
            if (device != null) {
                device.socket.abort();
            }
            if (outside != null) {
                outside.socket.abort();
            }
        }
    }

    @Test
    void separateMembershipsKeepOneAccountPerRoomAndIndependentPresence() throws Exception {
        Long owner = createUser("account-seats");
        ChatRoom room = roomFor("41135");
        ChatRoom otherRoom = roomFor("11680");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity=1 WHERE id IN (?,?)", room.getId(), otherRoom.getId());
        Long firstId = createSessionMembership(owner, room.getId());
        Long secondId = createSessionMembership(owner, room.getId());
        Long outsideId = createSessionMembership(owner, otherRoom.getId());
        SocketProbe first = null;
        SocketProbe second = null;
        SocketProbe outside = null;
        try {
            first = connectSocket(owner, room.getId(), firstId);
            subscribeMessages(first, room.getId());
            assertPresence(first, 1);
            second = connectSocket(owner, room.getId(), secondId);
            subscribeMessages(second, room.getId());
            assertPresence(second, 1);
            assertEquals(null, first.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            outside = connectSocket(owner, otherRoom.getId(), outsideId);
            subscribeMessages(outside, otherRoom.getId());
            assertPresence(outside, 1);
            assertEquals(null, first.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            service.leave(owner, memberRepository.findById(secondId).orElseThrow().getAuthSessionId(), room.getId(),
                    secondId);
            assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
            assertFalse(first.closed.isDone());
            assertFalse(outside.closed.isDone());
            assertEquals(null, first.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "remaining connection");
            assertTrue(nextFrame(first).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(first).contains("CHAT_ACK"));
            assertEquals(null, outside.frames.poll(150, TimeUnit.MILLISECONDS));
            service.leave(owner, memberRepository.findById(firstId).orElseThrow().getAuthSessionId(), room.getId(),
                    firstId);
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertFalse(outside.closed.isDone());
            Thread.sleep(1050); // Account-wide rate limiting also applies across rooms.
            sendMessage(outside, otherRoom.getId(), java.util.UUID.randomUUID().toString(), "independent room");
            assertTrue(nextFrame(outside).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(outside).contains("CHAT_ACK"));
        } finally {
            service.leave(owner, memberRepository.findById(firstId).orElseThrow().getAuthSessionId(), room.getId(),
                    firstId);
            service.leave(owner, memberRepository.findById(secondId).orElseThrow().getAuthSessionId(), room.getId(),
                    secondId);
            service.leave(owner, memberRepository.findById(outsideId).orElseThrow().getAuthSessionId(),
                    otherRoom.getId(), outsideId);
            for (SocketProbe socket : new SocketProbe[]{first, second, outside}) {
                if (socket != null) {
                    socket.socket.abort();
                }
            }
        }
    }

    private Long createSessionMembership(Long userId, Long roomId) {
        // B4 will provide REST creation; B3 validates counting with explicit B2-compatible fixtures.
        String sid = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO chat_room_members (user_id,room_id,auth_session_id) VALUES (?,?,?)",
                userId, roomId, sid);
        return jdbcTemplate.queryForObject("SELECT id FROM chat_room_members WHERE auth_session_id=?", Long.class, sid);
    }

    @Test
    void concurrentDifferentSessionsCreateDistinctMembershipsWithoutExceedingAccountCapacity() throws Exception {
        Long owner = createUser("device-concurrent");
        Long outsider = createUser("device-outsider");
        ChatRoom room = roomFor("41135");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity=1 WHERE id=?", room.getId());
        String firstSid = sid(owner);
        String secondSid = java.util.UUID.randomUUID().toString();
        String location = issueToken(owner, room.getRegion());
        var results = runConcurrently(
                () -> service.join(owner, firstSid, room.getId(), location),
                () -> service.join(owner, secondSid, room.getId(), issueToken(owner, room.getRegion(), secondSid)),
                () -> service.join(owner, firstSid, room.getId(), location));
        assertEquals(2L, results.stream().map(result -> result.membership().membershipId()).distinct().count());
        assertEquals(2L, results.stream().filter(ChatRoomJoinResult::created).count());
        assertEquals(2L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(room.getId()));
        assertEquals("full", joinOutcome(outsider, room.getId(), issueToken(outsider, room.getRegion())));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_room_members"
                + " WHERE auth_session_id=? AND deleted_at IS NULL", Long.class, firstSid));
    }

    @Test
    void differentSessionCanJoinAnotherRegionWithoutEndingTheOriginalParticipation() {
        Long owner = createUser("device-regions");
        ChatRoom first = roomFor("41135");
        ChatRoom second = roomFor("11680");
        Long firstMembership = service.join(owner, sid(owner), first.getId(), issueToken(owner, first.getRegion()))
                .membership().membershipId();
        String otherSid = java.util.UUID.randomUUID().toString();
        Long secondMembership = service
                .join(owner, otherSid, second.getId(), issueToken(owner, second.getRegion(), otherSid))
                .membership().membershipId();
        assertTrue(!firstMembership.equals(secondMembership));
        assertEquals(2, memberRepository.findAllByUser_IdAndDeletedAtIsNull(owner).size());
        assertTrue(memberRepository.findById(firstMembership).orElseThrow().isActive());
        assertEquals(otherSid, memberRepository.findById(secondMembership).orElseThrow().getAuthSessionId());
    }

    @Test
    void legacyMembershipRemainsUnclaimedAndCannotBeUsedByANewSession() {
        Long owner = createUser("device-legacy");
        ChatRoom room = roomFor("41135");
        jdbcTemplate.update("INSERT INTO chat_room_members (user_id,room_id) VALUES (?,?)", owner, room.getId());
        Long legacy = jdbcTemplate.queryForObject("SELECT id FROM chat_room_members WHERE user_id=?", Long.class,
                owner);
        Long current = service.join(owner, sid(owner), room.getId(), issueToken(owner, room.getRegion()))
                .membership().membershipId();
        assertTrue(!legacy.equals(current));
        assertEquals(null, memberRepository.findById(legacy).orElseThrow().getAuthSessionId());
        assertTrue(memberRepository.findById(legacy).orElseThrow().isActive());
        assertEquals(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND, org.junit.jupiter.api.Assertions.assertThrows(
                ChatRoomException.class, () -> service.connect(owner, sid(owner), room.getId(), legacy, "legacy"))
                .getErrorCode());
        assertTrue(memberRepository.findById(current).orElseThrow().isActive());
    }

    @Test
    void realHttpAndSocketsBindParticipationToSignedSessionAndRejectCrossDeviceLeave() throws Exception {
        Long owner = createUser("device-http");
        ChatRoom room = roomFor("41135");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity=1 WHERE id=?", room.getId());
        var user = users.findById(owner).orElseThrow();
        String firstToken = jwt.createAccessToken(user, sid(owner));
        String otherSid = java.util.UUID.randomUUID().toString();
        String secondToken = jwt.createAccessToken(user, otherSid);
        String path = "/api/v1/chat-rooms/" + room.getId() + "/members";
        String location = issueToken(owner, room.getRegion());
        assertEquals(401, membershipHttp("POST", path, jwt.createAccessToken(user), location).statusCode());
        assertEquals(401, membershipHttp("POST", path, jwt.createAccessToken(user, "invalid"), location).statusCode());
        var firstJoin = membershipHttp("POST", path, firstToken, location);
        assertEquals(201, firstJoin.statusCode());
        var secondJoin = membershipHttp("POST", path, secondToken, issueToken(owner, room.getRegion(), otherSid));
        assertEquals(201, secondJoin.statusCode());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Long firstId = mapper.readTree(firstJoin.body()).path("data").path("membership_id").longValue();
        Long secondId = mapper.readTree(secondJoin.body()).path("data").path("membership_id").longValue();
        var retry = membershipHttp("POST", path, firstToken, location);
        assertEquals(200, retry.statusCode());
        assertEquals(firstId.longValue(), mapper.readTree(retry.body()).path("data").path("membership_id").longValue());
        assertTrue(!firstId.equals(secondId));
        assertEquals(sid(owner), memberRepository.findById(firstId).orElseThrow().getAuthSessionId());
        assertEquals(otherSid, memberRepository.findById(secondId).orElseThrow().getAuthSessionId());
        String viewId = mapper.readTree(firstJoin.body()).path("data").path("view_session_id").asText();
        String retryView = mapper.readTree(retry.body()).path("data").path("view_session_id").asText();
        service.leaveTab(owner, sid(owner), room.getId(), firstId, retryView);
        SocketProbe first = connectSocket(owner, room.getId(), firstId, firstToken, viewId);
        SocketProbe second = connectSocket(owner, room.getId(), secondId, secondToken);
        SocketProbe spoofed = new SocketProbe();
        try {
            spoofed.socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                    .header("Origin", "http://localhost:5174").subprotocols("v12.stomp")
                    .buildAsync(java.net.URI.create("ws://localhost:" + port + "/ws"), spoofed)
                    .get(5, TimeUnit.SECONDS);
            spoofed.socket.sendText("CONNECT\naccept-version:1.2\nAuthorization:Bearer " + secondToken
                    + "\nroom_id:" + room.getId() + "\nmembership_id:" + firstId + "\n\n\u0000", true).join();
            String rejected = spoofed.frames.poll(5, TimeUnit.SECONDS);
            assertTrue(rejected != null && rejected.startsWith("ERROR"));
            assertEquals(1, authSessions.connections(owner, otherSid).size());
            assertEquals(403,
                    membershipHttp("DELETE", path + "/me?membership_id=" + firstId + "&view_session_id=" + viewId,
                            secondToken, null).statusCode());
            assertTrue(memberRepository.findById(firstId).orElseThrow().isActive());
            assertFalse(first.closed.isDone());
            assertFalse(second.closed.isDone());
            assertEquals(204,
                    membershipHttp("DELETE", path + "/me?membership_id=" + firstId + "&view_session_id=" + viewId,
                            firstToken, null).statusCode());
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertTrue(memberRepository.findById(secondId).orElseThrow().isActive());
            var rejoined = membershipHttp("POST", path, firstToken, location);
            assertEquals(201, rejoined.statusCode());
            assertTrue(firstId.longValue() != mapper.readTree(rejoined.body()).path("data")
                    .path("membership_id").longValue());
            assertFalse(memberRepository.findById(firstId).orElseThrow().isActive());
            subscribeMessages(second, room.getId());
            assertPresence(second, 1);
            sendMessage(second, room.getId(), java.util.UUID.randomUUID().toString(), "other device remains");
            assertTrue(nextFrame(second).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(second).contains("CHAT_ACK"));
        } finally {
            service.leaveAll(owner);
            first.socket.abort();
            second.socket.abort();
            if (spoofed.socket != null) {
                spoofed.socket.abort();
            }
        }
    }

    @Test
    void tabLeaveClosesOnlyItsViewAndKeepsSameSessionAndOtherDeviceChatting() throws Exception {
        Long owner = createUser("tab-leave");
        ChatRoom room = roomFor("41135");
        String location = issueToken(owner, room.getRegion());
        var first = service.join(owner, sid(owner), room.getId(), location).membership();
        var second = service.join(owner, sid(owner), room.getId(), location).membership();
        String otherSid = java.util.UUID.randomUUID().toString();
        var other = service.join(owner, otherSid, room.getId(), issueToken(owner, room.getRegion(), otherSid))
                .membership();
        assertEquals(first.membershipId(), second.membershipId());
        assertFalse(first.viewSessionId().equals(second.viewSessionId()));
        String token = jwt.createAccessToken(users.findById(owner).orElseThrow(), sid(owner));
        String deviceToken = jwt.createAccessToken(users.findById(owner).orElseThrow(), otherSid);
        SocketProbe tab = connectSocket(owner, room.getId(), first.membershipId(), token, first.viewSessionId());
        SocketProbe remaining = connectSocket(owner, room.getId(), second.membershipId(), token,
                second.viewSessionId());
        SocketProbe device = connectSocket(owner, room.getId(), other.membershipId(), deviceToken,
                other.viewSessionId());
        try {
            subscribeMessages(tab, room.getId());
            subscribeMessages(remaining, room.getId());
            subscribeMessages(device, room.getId());
            assertPresence(tab, 1);
            assertPresence(remaining, 1);
            assertPresence(device, 1);
            String path = "/api/v1/chat-rooms/" + room.getId() + "/members/me?membership_id="
                    + first.membershipId();
            assertEquals(400, membershipHttp("DELETE", path, token, null).statusCode());
            path += "&view_session_id=" + first.viewSessionId();
            assertEquals(403, membershipHttp("DELETE", path, deviceToken, null).statusCode());
            assertEquals(204, membershipHttp("DELETE", path, token, null).statusCode());
            assertEquals(4100, tab.closed.get(5, TimeUnit.SECONDS));
            assertEquals(204, membershipHttp("DELETE", path, token, null).statusCode());
            assertFalse(remaining.closed.isDone());
            assertFalse(device.closed.isDone());
            assertTrue(memberRepository.findById(first.membershipId()).orElseThrow().isActive());
            assertEquals(null, remaining.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            sendMessage(remaining, room.getId(), java.util.UUID.randomUUID().toString(), "remaining tab");
            assertTrue(nextFrame(remaining).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(remaining).contains("CHAT_ACK"));
            assertTrue(nextFrame(device).contains("CHAT_MESSAGE"));
            assertTrue(jdbcTemplate.queryForObject("SELECT ended_at IS NOT NULL FROM chat_view_sessions WHERE id=?",
                    Boolean.class, first.viewSessionId()));
            assertEquals("LEFT", jdbcTemplate.queryForObject(
                    "SELECT end_reason FROM chat_view_intervals WHERE view_session_id=?", String.class,
                    first.viewSessionId()));
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                    + "/api/v1/chat-rooms/" + room.getId() + "/messages?membership_id=" + first.membershipId()))
                    .header("Authorization", "Bearer " + token).header("X-Chat-View-Session", first.viewSessionId())
                    .GET().build();
            assertEquals(403, java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            service.leaveTab(owner, sid(owner), room.getId(), second.membershipId(), second.viewSessionId());
            assertEquals(4100, remaining.closed.get(5, TimeUnit.SECONDS));
            assertFalse(memberRepository.findById(first.membershipId()).orElseThrow().isActive());
            assertTrue(memberRepository.findById(other.membershipId()).orElseThrow().isActive());
            assertFalse(device.closed.isDone());
        } finally {
            service.leaveAll(owner);
            tab.socket.abort();
            remaining.socket.abort();
            device.socket.abort();
        }
    }

    @Test
    void pendingTabCleanupKeepsOtherReservationAndLastLeaveImmediatelyReleasesCapacity() {
        Long owner = createUser("tab-pending");
        Long outsider = createUser("tab-newcomer");
        ChatRoom room = roomFor("41135");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity=1 WHERE id=?", room.getId());
        var first = service.join(owner, sid(owner), room.getId(), issueToken(owner, room.getRegion())).membership();
        var second = service.join(owner, sid(owner), room.getId(), issueToken(owner, room.getRegion())).membership();
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_view_intervals", Integer.class));
        service.leaveTab(owner, sid(owner), room.getId(), first.membershipId(), first.viewSessionId());
        assertEquals("full", joinOutcome(outsider, room.getId(), issueToken(outsider, room.getRegion())));
        assertTrue(memberRepository.findById(first.membershipId()).orElseThrow().isActive());
        service.leaveTab(owner, sid(owner), room.getId(), second.membershipId(), second.viewSessionId());
        assertEquals("joined", joinOutcome(outsider, room.getId(), issueToken(outsider, room.getRegion())));
        service.leaveTab(owner, sid(owner), room.getId(), first.membershipId(), first.viewSessionId());
        assertFalse(memberRepository.findById(first.membershipId()).orElseThrow().isActive());
    }

    @Test
    void confirmedRegionDepartureStillClosesAllTabsOfOnlyMovingSession() throws Exception {
        Long owner = createUser("tab-region");
        ChatRoom room = roomFor("41135");
        ChatRoom destination = roomFor("11680");
        var first = service.join(owner, sid(owner), room.getId(), issueToken(owner, room.getRegion())).membership();
        var second = service.join(owner, sid(owner), room.getId(), issueToken(owner, room.getRegion())).membership();
        String otherSid = java.util.UUID.randomUUID().toString();
        var device = service.join(owner, otherSid, room.getId(), issueToken(owner, room.getRegion(), otherSid))
                .membership();
        String token = jwt.createAccessToken(users.findById(owner).orElseThrow(), sid(owner));
        SocketProbe a = connectSocket(owner, room.getId(), first.membershipId(), token, first.viewSessionId());
        SocketProbe b = connectSocket(owner, room.getId(), second.membershipId(), token, second.viewSessionId());
        String path = "/api/v1/chat-rooms/" + room.getId() + "/region-departures?membership_id=" + first.membershipId();
        try {
            assertEquals(400, membershipHttp("POST", path, token, issueToken(owner, room.getRegion())).statusCode());
            assertEquals(400, membershipHttp("POST", path, token,
                    issueToken(owner, destination.getRegion(), otherSid)).statusCode());
            assertFalse(a.closed.isDone());
            assertFalse(b.closed.isDone());
            assertEquals(204, membershipHttp("POST", path, token,
                    issueToken(owner, destination.getRegion())).statusCode());
            assertEquals(4100, a.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4100, b.closed.get(5, TimeUnit.SECONDS));
            assertTrue(memberRepository.findById(device.membershipId()).orElseThrow().isActive());
        } finally {
            service.leaveAll(owner);
            a.socket.abort();
            b.socket.abort();
        }
    }

    private java.net.http.HttpResponse<String> membershipHttp(String method, String path, String token,
            String location) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Origin", "http://localhost:5174");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                            java.util.Map.of("location_resolution_token", location))));
        } else {
            builder.DELETE();
        }
        return java.net.http.HttpClient.newHttpClient().send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void regionTransitionEndsOnlyMovingSessionEvenWhenDestinationIsFull(boolean destinationFull) throws Exception {
        Long owner = createUser("region-sessions");
        ChatRoom origin = roomFor("41135");
        ChatRoom destination = roomFor("11680");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity=1 WHERE id=?", destination.getId());
        if (destinationFull) {
            Long occupant = createUser("region-occupant");
            service.join(occupant, sid(occupant), destination.getId(), issueToken(occupant, destination.getRegion()));
        }
        String secondSid = java.util.UUID.randomUUID().toString();
        Long movingMembership = service.join(owner, sid(owner), origin.getId(), issueToken(owner, origin.getRegion()))
                .membership().membershipId();
        Long stayingMembership = service.join(owner, secondSid, origin.getId(),
                issueToken(owner, origin.getRegion(), secondSid)).membership().membershipId();
        var user = users.findById(owner).orElseThrow();
        String movingToken = jwt.createAccessToken(user, sid(owner));
        String stayingToken = jwt.createAccessToken(user, secondSid);
        SocketProbe moving = connectSocket(owner, origin.getId(), movingMembership, movingToken);
        SocketProbe staying = connectSocket(owner, origin.getId(), stayingMembership, stayingToken);
        SocketProbe destinationSocket = null;
        try {
            subscribeMessages(staying, origin.getId());
            assertPresence(staying, 1);
            String path = "/api/v1/chat-rooms/" + destination.getId() + "/members";
            String foreignEvidence = issueToken(owner, destination.getRegion(), secondSid);
            assertEquals(400, membershipHttp("POST", path, movingToken, foreignEvidence).statusCode());
            assertEquals(400, membershipHttp("POST", path, movingToken,
                    issueToken(owner, origin.getRegion())).statusCode());
            assertTrue(memberRepository.findById(movingMembership).orElseThrow().isActive());
            assertTrue(memberRepository.findById(stayingMembership).orElseThrow().isActive());
            assertFalse(moving.closed.isDone());
            org.mockito.Mockito.when(coordinates.resolve(37.3595704, 127.105399)).thenReturn(
                    new com.muse.meomuneum.location.provider.ResolvedRegionCode("11", "11680"));
            var request = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/locations/resolve"))
                    .header("Authorization", "Bearer " + movingToken).header("Origin", "http://localhost:5174")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"latitude\":37.3595704,\"longitude\":127.105399,\"accuracy_meters\":10}"))
                    .build();
            var resolved = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolved.statusCode());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String locationToken = mapper.readTree(resolved.body()).path("data").path("location_resolution_token")
                    .asText();
            assertEquals(sid(owner), tokenProvider.validateForChat(locationToken, owner, sid(owner)).authSessionId());
            assertEquals(400, membershipHttp("POST", path, stayingToken, locationToken).statusCode());
            var result = membershipHttp("POST", path, movingToken, locationToken);
            assertEquals(destinationFull ? 409 : 201, result.statusCode());
            assertEquals(4100, moving.closed.get(5, TimeUnit.SECONDS));
            assertFalse(memberRepository.findById(movingMembership).orElseThrow().isActive());
            assertTrue(memberRepository.findById(stayingMembership).orElseThrow().isActive());
            assertFalse(staying.closed.isDone());
            assertEquals(null, staying.presenceFrames.poll(150, TimeUnit.MILLISECONDS));
            sendMessage(staying, origin.getId(), java.util.UUID.randomUUID().toString(), "staying device");
            assertTrue(nextFrame(staying).contains("CHAT_MESSAGE"));
            assertTrue(nextFrame(staying).contains("CHAT_ACK"));
            if (!destinationFull) {
                Long movedId = mapper.readTree(result.body()).path("data").path("membership_id").longValue();
                destinationSocket = connectSocket(owner, destination.getId(), movedId, movingToken);
                subscribeMessages(destinationSocket, destination.getId());
                assertPresence(destinationSocket, 1);
            } else {
                assertTrue(
                        memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(owner, sid(owner)).isEmpty());
            }
        } finally {
            service.leaveAll(owner);
            moving.socket.abort();
            staying.socket.abort();
            if (destinationSocket != null) {
                destinationSocket.socket.abort();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_RECOVERY_FE_DIR", matches = ".+")
    void frontendRegionClientUsesSessionBoundEvidenceAndKeepsTheOtherDeviceInPlace() throws Exception {
        Long owner = createUser("region-frontend");
        var user = users.findById(owner).orElseThrow();
        ChatRoom origin = roomFor("41135");
        ChatRoom destination = roomFor("11680");
        org.mockito.Mockito.when(coordinates.resolve(37.3595704, 127.105399)).thenReturn(
                new com.muse.meomuneum.location.provider.ResolvedRegionCode("41", "41135"));
        org.mockito.Mockito.when(coordinates.resolve(37.3595705, 127.105399)).thenReturn(
                new com.muse.meomuneum.location.provider.ResolvedRegionCode("11", "11680"));
        var process = new ProcessBuilder("node", "node_modules/vitest/vitest.mjs", "run",
                "src/features/chat-entry/services/chatRegionTransition.integration.test.ts")
                .directory(java.nio.file.Path.of(System.getenv("CHAT_RECOVERY_FE_DIR")).toFile())
                .redirectErrorStream(true);
        process.environment().put("CHAT_REGION_URL", "http://localhost:" + port);
        process.environment().put("CHAT_REGION_TOKEN_A", jwt.createAccessToken(user, sid(owner)));
        process.environment().put("CHAT_REGION_TOKEN_B",
                jwt.createAccessToken(user, java.util.UUID.randomUUID().toString()));
        process.environment().put("CHAT_REGION_ORIGIN", origin.getRegion().getId().toString());
        process.environment().put("CHAT_REGION_DESTINATION", destination.getRegion().getId().toString());
        Process running = process.start();
        try {
            if (!running.waitFor(45, TimeUnit.SECONDS)) {
                running.destroyForcibly();
                throw new AssertionError("frontend region integration timed out");
            }
            String output = new String(running.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, running.exitValue(), output);
            assertEquals(2, memberRepository.findAllByUser_IdAndDeletedAtIsNull(owner).size());
        } finally {
            service.leaveAll(owner);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CHAT_RECOVERY_FE_DIR", matches = ".+")
    void actualFrontendTabLeaveAndLateJoinKeepOtherTabActive() throws Exception {
        Long owner = createUser("tab-frontend");
        ChatRoom room = roomFor("41135");
        var process = new ProcessBuilder("node", "node_modules/vitest/vitest.mjs", "run",
                "src/features/chat-entry/services/chatTabLeave.integration.test.ts")
                .directory(java.nio.file.Path.of(System.getenv("CHAT_RECOVERY_FE_DIR")).toFile())
                .redirectErrorStream(true);
        process.environment().put("CHAT_TAB_URL", "http://localhost:" + port);
        process.environment().put("CHAT_TAB_TOKEN",
                jwt.createAccessToken(users.findById(owner).orElseThrow(), sid(owner)));
        process.environment().put("CHAT_TAB_LOCATION", issueToken(owner, room.getRegion()));
        process.environment().put("CHAT_TAB_REGION", room.getRegion().getId().toString());
        Process running = process.start();
        try {
            if (!running.waitFor(45, TimeUnit.SECONDS)) {
                running.destroyForcibly();
                throw new AssertionError("frontend tab leave integration timed out");
            }
            String output = new String(running.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, running.exitValue(), output);
            assertEquals(0, memberRepository.findAllByUser_IdAndDeletedAtIsNull(owner).size());
        } finally {
            service.leaveAll(owner);
        }
    }

    private void assertPresence(SocketProbe probe, int count) throws Exception {
        String frame = probe.presenceFrames.poll(5, TimeUnit.SECONDS);
        assertTrue(frame != null, "expected presence event");
        assertTrue(frame.contains("\"connected_count\":" + count));
        assertTrue(frame.contains("\"version\":"));
    }

    private String nextFrame(SocketProbe probe) throws Exception {
        String frame = probe.frames.poll(5, TimeUnit.SECONDS);
        assertTrue(frame != null, "expected a STOMP frame");
        return frame;
    }

    private void subscribeMessages(SocketProbe probe, Long roomId) throws Exception {
        probe.socket.sendText("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + roomId
                + "\nreceipt:room\n\n\u0000", true).join();
        assertTrue(nextFrame(probe).startsWith("RECEIPT"));
        probe.socket.sendText("SUBSCRIBE\nid:events\ndestination:/user/queue/chat-events"
                + "\nreceipt:events\n\n\u0000", true).join();
        assertTrue(nextFrame(probe).startsWith("RECEIPT"));
    }

    private void sendMessage(SocketProbe probe, Long roomId, String id, String content) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("client_message_id", id, "content", content));
        probe.socket.sendText("SEND\ndestination:/app/chat-rooms/" + roomId
                + "/messages\ncontent-type:application/json\n\n" + body + "\u0000", true).join();
    }

    private SocketProbe connectSocket(Long userId, Long roomId, Long membershipId) throws Exception {
        return connectSocket(userId, roomId, membershipId,
                jwt.createAccessToken(users.findById(userId).orElseThrow(),
                        memberRepository.findById(membershipId).orElseThrow().getAuthSessionId()));
    }

    private SocketProbe connectSocket(Long userId, Long roomId, Long membershipId, String token) throws Exception {
        return connectSocket(userId, roomId, membershipId, token, null);
    }

    private SocketProbe connectSocket(Long userId, Long roomId, Long membershipId, String token, String viewId)
            throws Exception {
        SocketProbe probe = new SocketProbe();
        probe.socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5174").subprotocols("v12.stomp")
                .buildAsync(java.net.URI.create("ws://localhost:" + port + "/ws"), probe).get(5, TimeUnit.SECONDS);
        probe.socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token
                + "\nroom_id:" + roomId + "\nmembership_id:" + membershipId
                + (viewId == null ? "" : "\nview_session_id:" + viewId) + "\n\n\u0000", true).join();
        assertTrue(probe.frames.poll(5, TimeUnit.SECONDS).startsWith("CONNECTED"));
        return probe;
    }

    private static final class SocketProbe implements java.net.http.WebSocket.Listener {
        private java.net.http.WebSocket socket;
        private final BlockingQueue<String> presenceFrames = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> viewFrames = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private String closeReason;
        private final StringBuilder text = new StringBuilder();

        @Override
        public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket, CharSequence data,
                boolean last) {
            text.append(data);
            if (last) {
                if (text.toString().contains("CHAT_PRESENCE")) {
                    presenceFrames.add(text.toString());
                } else if (text.toString().contains("CHAT_VIEW_READY") || text.toString().contains("CHAT_READY")) {
                    viewFrames.add(text.toString());
                } else {
                    frames.add(text.toString());
                }
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(java.net.http.WebSocket socket,
                java.nio.ByteBuffer data, boolean last) {
            return onText(socket, java.nio.charset.StandardCharsets.UTF_8.decode(data), last);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket socket, int status,
                String reason) {
            closeReason = reason;
            closed.complete(status);
            return null;
        }
    }

    private String joinOutcome(Long userId, Long roomId, String token) {
        try {
            service.join(userId, sid(userId), roomId, token);
            return "joined";
        } catch (ChatRoomException exception) {
            if (exception.getErrorCode() == ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED) {
                return "full";
            }
            throw exception;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out while coordinating concurrent joins");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Long createUser(String suffix) {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES (?, 'password-hash', ?, 'USER')
                """, "chat-entry-" + suffix + "@example.com", suffix.substring(0, Math.min(12, suffix.length())));
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "chat-entry-" + suffix + "@example.com");
    }

    private ChatRoom roomFor(String sigunguCode) {
        Region region = regionRepository.findByCodeAndLevelAndActiveTrue(sigunguCode, RegionLevel.SIGUNGU)
                .orElseThrow();
        return chatRoomRepository.findByRegion_Id(region.getId()).orElseThrow();
    }

    private String issueToken(Long userId, Region sigungu) {
        return issueToken(userId, sigungu, sid(userId));
    }

    private String issueToken(Long userId, Region sigungu, String authSessionId) {
        Long sigunguId = sigungu.getId();
        return new TransactionTemplate(transactionManager).execute(status -> {
            Region managedSigungu = regionRepository.findById(sigunguId).orElseThrow();
            Region sido = managedSigungu.getParent();
            IssuedLocationToken token = tokenProvider.issueForSession(userId, authSessionId, sido, managedSigungu,
                    null);
            return token.value();
        });
    }

    @SafeVarargs
    private <T> List<T> runConcurrently(Callable<T>... tasks) throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.Arrays.stream(tasks).map(task -> executor.submit(() -> {
                start.await();
                return task.call();
            })).toList();
            start.countDown();
            return futures.stream().map(this::getResult).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> T getResult(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception.getCause());
        }
    }
}
