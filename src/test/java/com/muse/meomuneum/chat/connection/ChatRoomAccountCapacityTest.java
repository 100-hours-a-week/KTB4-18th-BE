package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.message.policy.ChatMessagePolicyProperties;
import com.muse.meomuneum.chat.message.policy.ChatTransmissionPolicy;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;

class ChatRoomAccountCapacityTest {
    private final Clock clock = mock(Clock.class);
    private final Instant start = Instant.parse("2026-10-16T00:00:00Z");
    private final List<ChatPresenceChangedEvent> changes = new ArrayList<>();

    private ChatPresenceRegistry registry() {
        when(clock.instant()).thenReturn(start);
        return new ChatPresenceRegistry(clock,
                new ChatTransmissionPolicy(clock, new ChatMessagePolicyProperties.Rules(null, true)), event -> {
                    if (event instanceof ChatPresenceChangedEvent change) {
                        changes.add(change);
                    }
                });
    }

    @Test
    void fullRoomAcceptsMoreMembershipsOfExistingAccountButRejectsNewAccount() {
        ChatPresenceRegistry registry = registry();
        for (long user = 1; user <= 25; user++) {
            registry.connect(user, 10L, user, 25, "socket-" + user);
        }
        registry.reserve(1L, 10L, 100L);
        registry.connect(1L, 10L, 100L, 25, "other-device");
        registry.connect(1L, 10L, 100L, 25, "other-tab");
        assertEquals(25, registry.connectedCount(10L));
        assertEquals(25, changes.size());
        assertTrue(registry.hasCapacity(1L, 10L, 25));
        assertFalse(registry.hasCapacity(26L, 10L, 25));
        ChatRoomException rejected = assertThrows(ChatRoomException.class,
                () -> registry.connect(26L, 10L, 260L, 25, "overflow"));
        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, rejected.getErrorCode());
        registry.remove(1L, 1L);
        assertEquals(25, registry.connectedCount(10L));
        assertFalse(registry.hasCapacity(26L, 10L, 25));
        registry.remove(1L, 100L);
        assertTrue(registry.hasCapacity(26L, 10L, 25));
        assertEquals(24, registry.connectedCount(10L));
    }

    @Test
    void sameAccountOccupiesIndependentRoomsAndKeepsSubscriptionsAndRevisions() {
        ChatPresenceRegistry registry = registry();
        registry.connect(1L, 10L, 100L, 1, "first-room");
        registry.connect(1L, 10L, 101L, 1, "same-room-device");
        registry.connect(1L, 20L, 200L, 1, "second-room");
        registry.subscribed("first-room", "sub-a", "/topic/chat-rooms/10");
        registry.subscribed("same-room-device", "sub-b", "/topic/chat-rooms/10");
        registry.subscribed("second-room", "sub-c", "/topic/chat-rooms/20");
        assertEquals(Set.of("first-room", "same-room-device"), registry.recipients(10L));
        assertEquals(Map.of("second-room", Set.of("sub-c")), registry.roomSubscriptions(20L));
        assertEquals(1, registry.connectedCount(10L));
        assertEquals(1, registry.connectedCount(20L));
        assertEquals(2, changes.size());
        registry.remove(1L, 100L);
        assertEquals(2, changes.size());
        registry.disconnect("same-room-device");
        assertEquals(3, changes.size());
        assertEquals(10L, changes.getLast().roomId());
        assertEquals(2L, changes.getLast().revision());
        assertEquals(Set.of("second-room"), registry.recipients(20L));
        registry.sendSnapshot("second-room", "sub-c", "/topic/chat-rooms/20");
        assertEquals(1L, changes.getLast().revision());
        assertEquals(1, changes.getLast().count());
        assertTrue(registry.ownsConnection(1L, 200L, "second-room"));
    }

    @Test
    void reservationsExpireIndividuallyAndRetriesDoNotExtendTheirDeadline() {
        ChatPresenceRegistry registry = registry();
        registry.reserve(1L, 10L, 100L);
        when(clock.instant()).thenReturn(start.plusSeconds(10));
        registry.reserve(1L, 10L, 101L);
        assertTrue(registry.hasCapacity(2L, 10L, 2));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        assertEquals(0, registry.connectedCount(10L));
        when(clock.instant()).thenReturn(start.plusSeconds(29));
        registry.reserve(1L, 10L, 100L);
        when(clock.instant()).thenReturn(start.plusSeconds(30));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        when(clock.instant()).thenReturn(start.plusSeconds(40));
        assertTrue(registry.hasCapacity(2L, 10L, 1));
        assertEquals(0, changes.size());
    }

    @Test
    void expiringDisconnectedMembershipDoesNotFreeAnotherMembershipsSeat() {
        ChatPresenceRegistry registry = registry();
        registry.connect(1L, 10L, 100L, 1, "device-a");
        registry.connect(1L, 10L, 101L, 1, "device-b");
        registry.disconnect("device-a");
        when(clock.instant()).thenReturn(start.plusSeconds(30));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        assertTrue(registry.ownsConnection(1L, 101L, "device-b"));
        assertEquals(1, registry.connectedCount(10L));
        registry.disconnect("device-b");
        assertEquals(0, registry.connectedCount(10L));
        when(clock.instant()).thenReturn(start.plusSeconds(59));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertTrue(registry.hasCapacity(2L, 10L, 1));
        assertEquals(2, changes.size());
    }

    @Test
    void graceAllowsAnotherMembershipToReconnectUntilLastReservationExpires() {
        ChatPresenceRegistry registry = registry();
        registry.connect(1L, 10L, 100L, 1, "old");
        registry.disconnect("old");
        when(clock.instant()).thenReturn(start.plusSeconds(29));
        registry.connect(1L, 10L, 101L, 1, "new-device");
        when(clock.instant()).thenReturn(start.plusSeconds(30));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        registry.remove(1L, 101L);
        registry.connect(2L, 10L, 200L, 1, "new-account");
        assertThrows(ChatRoomException.class,
                () -> registry.connect(1L, 10L, 100L, 1, "late"));
    }

    @Test
    void removingOneMembershipPreservesAnotherRoomsReservation() {
        ChatPresenceRegistry registry = registry();
        registry.reserve(1L, 10L, 100L);
        registry.reserve(1L, 20L, 200L);
        registry.remove(1L, 100L);
        assertTrue(registry.hasCapacity(2L, 10L, 1));
        assertFalse(registry.hasCapacity(2L, 20L, 1));
    }

    @Test
    void mismatchedOwnerOrRoomCannotReplaceOrRemoveMembershipState() {
        ChatPresenceRegistry registry = registry();
        registry.connect(1L, 10L, 100L, 2, "owner");
        assertThrows(ChatRoomException.class, () -> registry.reserve(2L, 10L, 100L));
        assertThrows(ChatRoomException.class, () -> registry.reserve(1L, 20L, 100L));
        assertEquals(Set.of(), registry.remove(2L, 100L));
        assertFalse(registry.ownsConnection(2L, 100L, "owner"));
        assertTrue(registry.ownsConnection(1L, 100L, "owner"));
        assertEquals(1, registry.connectedCount(10L));
        assertEquals(1, changes.size());
    }

    @Test
    void concurrentExtraMembershipsAndNewAccountsUseOnlyOneRemainingAccountSlot() throws Exception {
        ChatPresenceRegistry registry = registry();
        for (long user = 1; user <= 24; user++) {
            registry.connect(user, 10L, user, 25, "existing-" + user);
        }
        CountDownLatch startGate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (long attempt = 0; attempt < 40; attempt++) {
                final long number = attempt;
                results.add(executor.submit(() -> {
                    assertTrue(startGate.await(5, TimeUnit.SECONDS));
                    long user = number < 20 ? 1L : number + 25;
                    return registry.exclusive(() -> {
                        if (!registry.hasCapacity(user, 10L, 25)) {
                            return false;
                        }
                        registry.reserve(user, 10L, 1000 + number);
                        registry.connect(user, 10L, 1000 + number, 25, "concurrent-" + number);
                        return true;
                    });
                }));
            }
            startGate.countDown();
            int admitted = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(21, admitted);
        }
        assertEquals(25, registry.connectedCount(10L));
        assertEquals(25, changes.size());
        assertFalse(registry.hasCapacity(999L, 10L, 25));
    }
}
