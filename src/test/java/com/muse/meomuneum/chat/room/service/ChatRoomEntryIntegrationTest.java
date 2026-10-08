package com.muse.meomuneum.chat.room.service;

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
        jdbcTemplate.update("DELETE FROM chat_room_members");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'chat-entry-%'");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 25, status = 'ACTIVE'");
    }

    @Test
    void repeatedConcurrentJoinKeepsOneActiveMembership() throws Exception {
        Long userId = createUser("same-user");
        ChatRoom room = roomFor("41135");
        String token = issueToken(userId, room.getRegion());

        List<ChatRoomJoinResult> results = runConcurrently(() -> service.join(userId, room.getId(), token),
                () -> service.join(userId, room.getId(), token));

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
            service.join(firstUserId, room.getId(), firstToken);
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
        service.join(firstUserId, firstRoom.getId(), issueToken(firstUserId, firstRoom.getRegion()));
        service.join(secondUserId, secondRoom.getId(), issueToken(secondUserId, secondRoom.getRegion()));

        List<ChatRoomJoinResult> results = runConcurrently(
                () -> service.join(firstUserId, secondRoom.getId(), issueToken(firstUserId, secondRoom.getRegion())),
                () -> service.join(secondUserId, firstRoom.getId(), issueToken(secondUserId, firstRoom.getRegion())));

        assertEquals(2, results.size());
        assertEquals(secondRoom.getId(),
                memberRepository.findByUser_IdAndDeletedAtIsNull(firstUserId).orElseThrow().getChatRoom().getId());
        assertEquals(firstRoom.getId(),
                memberRepository.findByUser_IdAndDeletedAtIsNull(secondUserId).orElseThrow().getChatRoom().getId());
    }

    @Test
    void fullDestinationRoomDoesNotRestoreThePreviousMembership() {
        Long movingUserId = createUser("moving-user");
        Long occupyingUserId = createUser("occupying-user");
        ChatRoom previousRoom = roomFor("41135");
        ChatRoom fullRoom = roomFor("11680");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 1 WHERE id = ?", fullRoom.getId());

        service.join(movingUserId, previousRoom.getId(), issueToken(movingUserId, previousRoom.getRegion()));
        service.join(occupyingUserId, fullRoom.getId(), issueToken(occupyingUserId, fullRoom.getRegion()));

        ChatRoomException exception = org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.join(movingUserId, fullRoom.getId(), issueToken(movingUserId, fullRoom.getRegion())));

        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, exception.getErrorCode());
        assertFalse(memberRepository.findByUser_IdAndDeletedAtIsNull(movingUserId).isPresent());
        assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(fullRoom.getId()));
    }

    @Test
    void rejectsAValidTokenForAnotherRegion() {
        Long userId = createUser("wrong-region");
        ChatRoom requestedRoom = roomFor("41135");
        ChatRoom actualRoom = roomFor("11680");

        ChatRoomException exception = org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.join(userId, requestedRoom.getId(), issueToken(userId, actualRoom.getRegion())));

        assertEquals(ChatRoomErrorCode.LOCATION_REGION_MISMATCH, exception.getErrorCode());
        assertTrue(memberRepository.findByUser_IdAndDeletedAtIsNull(userId).isEmpty());
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
        Long previous = service.join(userId, room.getId(), token).membership().membershipId();
        service.leave(userId, room.getId(), previous);
        Long current = service.join(userId, room.getId(), token).membership().membershipId();
        service.leave(userId, room.getId(), previous);
        assertEquals(current, memberRepository.findByUser_IdAndDeletedAtIsNull(userId).orElseThrow().getId());
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.leave(otherId, room.getId(), current));
    }

    @Test
    void bannedUsersCannotJoinConnectOrSubscribe() {
        Long userId = createUser("banned");
        ChatRoom room = roomFor("41135");
        String token = issueToken(userId, room.getRegion());
        Long membershipId = service.join(userId, room.getId(), token).membership().membershipId();
        service.connect(userId, room.getId(), membershipId, "session");
        jdbcTemplate.update("""
                INSERT INTO chat_bans (user_id, reason, created_at, expires_at)
                VALUES (?, 'PROFANITY', UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY))
                """, userId);
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.join(userId, room.getId(), token));
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.connect(userId, room.getId(), membershipId, "second"));
        org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                () -> service.authorizeConnection(userId, room.getId(), membershipId, "session"));
    }

    @Test
    void browserStompAuthenticatesReceivesSubscriptionReceiptAndAccountWideLeaveClosesBothSockets()
            throws Exception {
        Long userId = createUser("websocket");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, room.getId(), issueToken(userId, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(userId, room.getId(), membershipId);
        SocketProbe second = connectSocket(userId, room.getId(), membershipId);
        try {
            first.socket.sendText("SUBSCRIBE\nid:room\ndestination:/topic/chat-rooms/" + room.getId()
                    + "\nreceipt:ready\n\n\u0000", true).join();
            assertTrue(first.frames.poll(5, TimeUnit.SECONDS).contains("receipt-id:ready"));
            service.leave(userId, room.getId(), membershipId);
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                    () -> service.connect(userId, room.getId(), membershipId, "late"));
        } finally {
            first.socket.abort();
            second.socket.abort();
        }
    }

    @Test
    void browserStompRejectsSubscriptionsToAnotherRoom() throws Exception {
        Long userId = createUser("cross-room");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, room.getId(), issueToken(userId, room.getRegion()))
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
    void logoutEndsMembershipAndAllAccountConnections() throws Exception {
        Long userId = createUser("logout");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(userId, room.getId(), issueToken(userId, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(userId, room.getId(), membershipId);
        SocketProbe second = connectSocket(userId, room.getId(), membershipId);
        try {
            events.publishEvent(new com.muse.meomuneum.auth.service.ChatLogoutEvent(userId));
            assertEquals(4100, first.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4100, second.closed.get(5, TimeUnit.SECONDS));
            assertTrue(memberRepository.findByUser_IdAndDeletedAtIsNull(userId).isEmpty());
        } finally {
            first.socket.abort();
            second.socket.abort();
        }
    }

    @Test
    void textDeliveryAcknowledgesOnceAndNeverReplaysToLaterOrOtherRoomSubscribers() throws Exception {
        Long firstId = createUser("message-first");
        Long secondId = createUser("message-second");
        Long thirdId = createUser("message-third");
        ChatRoom room = roomFor("41135");
        ChatRoom otherRoom = roomFor("11680");
        Long firstMembership = service.join(firstId, room.getId(), issueToken(firstId, room.getRegion()))
                .membership().membershipId();
        Long secondMembership = service.join(secondId, room.getId(), issueToken(secondId, room.getRegion()))
                .membership().membershipId();
        Long thirdMembership = service.join(thirdId, otherRoom.getId(), issueToken(thirdId, otherRoom.getRegion()))
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
        Long membershipId = service.join(userId, room.getId(), issueToken(userId, room.getRegion()))
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
    void reviewedProfanityBansAllAccountConnectionsAndBlocksEveryRoomWithoutStoringText() throws Exception {
        Long offenderId = createUser("message-ban");
        Long observerId = createUser("message-watch");
        ChatRoom room = roomFor("41135");
        Long membershipId = service.join(offenderId, room.getId(), issueToken(offenderId, room.getRegion()))
                .membership().membershipId();
        Long observerMembership = service.join(observerId, room.getId(), issueToken(observerId, room.getRegion()))
                .membership().membershipId();
        SocketProbe first = connectSocket(offenderId, room.getId(), membershipId);
        SocketProbe second = connectSocket(offenderId, room.getId(), membershipId);
        SocketProbe observer = connectSocket(observerId, room.getId(), observerMembership);
        try {
            subscribeMessages(first, room.getId());
            subscribeMessages(second, room.getId());
            subscribeMessages(observer, room.getId());
            sendMessage(first, room.getId(), java.util.UUID.randomUUID().toString(), "씨발");
            assertEquals(4101, first.closed.get(5, TimeUnit.SECONDS));
            assertEquals(4101, second.closed.get(5, TimeUnit.SECONDS));
            assertTrue(first.closeReason.startsWith("CHAT_BANNED|"));
            assertTrue(second.closeReason.startsWith("CHAT_BANNED|"));
            assertEquals(null, observer.frames.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_room_messages", Long.class));
            assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_bans", Long.class));
            assertTrue(memberRepository.findByUser_IdAndDeletedAtIsNull(offenderId).isEmpty());
            assertEquals(7, jdbcTemplate.queryForObject(
                    "SELECT TIMESTAMPDIFF(DAY, created_at, expires_at) FROM chat_bans", Integer.class));
            ChatRoom otherRoom = roomFor("11680");
            ChatRoomException denied = org.junit.jupiter.api.Assertions.assertThrows(ChatRoomException.class,
                    () -> service.join(offenderId, otherRoom.getId(), issueToken(offenderId, otherRoom.getRegion())));
            assertEquals(ChatRoomErrorCode.CHAT_BANNED, denied.getErrorCode());
            assertTrue(denied.getBannedUntil() != null);
            jdbcTemplate
                    .update("UPDATE chat_bans SET created_at = DATE_SUB(NOW(), INTERVAL 7 DAY), expires_at = NOW()");
            assertTrue(service.join(offenderId, otherRoom.getId(), issueToken(offenderId, otherRoom.getRegion()))
                    .created());
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
        Long ownMembership = service.join(owner, room.getId(), issueToken(owner, room.getRegion()))
                .membership().membershipId();
        Long otherMembership = service.join(other, room.getId(), issueToken(other, room.getRegion()))
                .membership().membershipId();
        Long outsiderMembership = service.join(outsider, otherRoom.getId(), issueToken(outsider, otherRoom.getRegion()))
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
            service.leave(other, room.getId(), otherMembership);
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
        SocketProbe probe = new SocketProbe();
        probe.socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5174").subprotocols("v12.stomp")
                .buildAsync(java.net.URI.create("ws://localhost:" + port + "/ws"), probe).get(5, TimeUnit.SECONDS);
        String token = jwt.createAccessToken(users.findById(userId).orElseThrow());
        probe.socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token
                + "\nroom_id:" + roomId + "\nmembership_id:" + membershipId + "\n\n\u0000", true).join();
        assertTrue(probe.frames.poll(5, TimeUnit.SECONDS).startsWith("CONNECTED"));
        return probe;
    }

    private static final class SocketProbe implements java.net.http.WebSocket.Listener {
        private java.net.http.WebSocket socket;
        private final BlockingQueue<String> presenceFrames = new LinkedBlockingQueue<>();
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
            service.join(userId, roomId, token);
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
        Long sigunguId = sigungu.getId();
        return new TransactionTemplate(transactionManager).execute(status -> {
            Region managedSigungu = regionRepository.findById(sigunguId).orElseThrow();
            Region sido = managedSigungu.getParent();
            IssuedLocationToken token = tokenProvider.issue(userId, sido, managedSigungu);
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
