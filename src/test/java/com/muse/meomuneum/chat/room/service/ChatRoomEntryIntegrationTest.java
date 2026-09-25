package com.muse.meomuneum.chat.room.service;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ChatRoomEntryIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.6")
            .withDatabaseName("meomuneum_chat_entry_test")
            .withUsername("meomuneum_test")
            .withPassword("meomuneum_test");

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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void resetEntryState() {
        jdbcTemplate.update("DELETE FROM chat_room_members");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'chat-entry-%'");
        jdbcTemplate.update("UPDATE chat_rooms SET capacity = 25, status = 'ACTIVE'");
    }

    @Test
    void repeatedConcurrentJoinKeepsOneActiveMembership() throws Exception {
        Long userId = createUser("same-user");
        ChatRoom room = roomFor("41135");
        String token = issueToken(userId, room.getRegion());

        List<ChatRoomJoinResult> results = runConcurrently(
                () -> service.join(userId, room.getId(), token),
                () -> service.join(userId, room.getId(), token)
        );

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

        List<String> outcomes = runConcurrently(
                () -> joinOutcome(firstUserId, room.getId(), firstToken),
                () -> joinOutcome(secondUserId, room.getId(), secondToken)
        );

        assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(room.getId()));
        assertEquals(1L, outcomes.stream().filter("joined"::equals).count());
        assertEquals(1L, outcomes.stream().filter("full"::equals).count());
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
                () -> service.join(
                        firstUserId,
                        secondRoom.getId(),
                        issueToken(firstUserId, secondRoom.getRegion())
                ),
                () -> service.join(
                        secondUserId,
                        firstRoom.getId(),
                        issueToken(secondUserId, firstRoom.getRegion())
                )
        );

        assertEquals(2, results.size());
        assertEquals(secondRoom.getId(), memberRepository.findByUser_IdAndDeletedAtIsNull(firstUserId)
                .orElseThrow().getChatRoom().getId());
        assertEquals(firstRoom.getId(), memberRepository.findByUser_IdAndDeletedAtIsNull(secondUserId)
                .orElseThrow().getChatRoom().getId());
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

        ChatRoomException exception = org.junit.jupiter.api.Assertions.assertThrows(
                ChatRoomException.class,
                () -> service.join(
                        movingUserId,
                        fullRoom.getId(),
                        issueToken(movingUserId, fullRoom.getRegion())
                )
        );

        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, exception.getErrorCode());
        assertFalse(memberRepository.findByUser_IdAndDeletedAtIsNull(movingUserId).isPresent());
        assertEquals(1L, memberRepository.countByChatRoom_IdAndDeletedAtIsNull(fullRoom.getId()));
    }

    @Test
    void rejectsAValidTokenForAnotherRegion() {
        Long userId = createUser("wrong-region");
        ChatRoom requestedRoom = roomFor("41135");
        ChatRoom actualRoom = roomFor("11680");

        ChatRoomException exception = org.junit.jupiter.api.Assertions.assertThrows(
                ChatRoomException.class,
                () -> service.join(
                        userId,
                        requestedRoom.getId(),
                        issueToken(userId, actualRoom.getRegion())
                )
        );

        assertEquals(ChatRoomErrorCode.LOCATION_REGION_MISMATCH, exception.getErrorCode());
        assertTrue(memberRepository.findByUser_IdAndDeletedAtIsNull(userId).isEmpty());
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

    private Long createUser(String suffix) {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES (?, 'password-hash', ?, 'USER')
                """, "chat-entry-" + suffix + "@example.com", suffix.substring(0, Math.min(12, suffix.length())));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                Long.class,
                "chat-entry-" + suffix + "@example.com"
        );
    }

    private ChatRoom roomFor(String sigunguCode) {
        Region region = regionRepository.findByCodeAndLevelAndActiveTrue(sigunguCode, RegionLevel.SIGUNGU)
                .orElseThrow();
        return chatRoomRepository.findByRegion_Id(region.getId()).orElseThrow();
    }

    private String issueToken(Long userId, Region sigungu) {
        Region sido = sigungu.getParent();
        IssuedLocationToken token = tokenProvider.issue(userId, sido, sigungu);
        return token.value();
    }

    @SafeVarargs
    private <T> List<T> runConcurrently(Callable<T>... tasks)
            throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.Arrays.stream(tasks)
                    .map(task -> executor.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
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
