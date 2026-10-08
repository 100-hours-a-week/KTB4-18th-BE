package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ChatPresenceRegistryTest {
    @Test
    void countsActualDistinctAccountsAndVersionsOnlyCountChanges() {
        Clock clock = Clock.systemUTC();
        java.util.List<ChatPresenceChangedEvent> events = new java.util.ArrayList<>();
        ChatPresenceRegistry registry = new ChatPresenceRegistry(clock,
                new com.muse.meomuneum.chat.message.policy.ChatTransmissionPolicy(clock,
                        new com.muse.meomuneum.chat.message.policy.ChatMessagePolicyProperties.Rules(
                                null, true, true, null, null)),
                event -> events.add((ChatPresenceChangedEvent) event));
        registry.reserve(1L, 10L, 100L);
        assertEquals(0, registry.connectedCount(10L));
        registry.connect(1L, 10L, 100L, 25, "first");
        registry.connect(1L, 10L, 100L, 25, "second");
        assertEquals(1, registry.connectedCount(10L));
        assertEquals(1, events.size());
        registry.subscribed("first", "room", "/topic/chat-rooms/10");
        registry.sendSnapshot("first", "room", "/topic/chat-rooms/10");
        assertEquals(1, events.getLast().revision());
        registry.disconnect("second");
        assertEquals(1, registry.connectedCount(10L));
        registry.disconnect("first");
        assertEquals(0, registry.connectedCount(10L));
        assertEquals(2, events.getLast().revision());
        assertEquals(3, events.size());
        registry.connect(1L, 10L, 100L, 25, "reconnected");
        assertEquals(3, events.getLast().revision());
        registry.remove(1L, 100L);
        assertEquals(0, registry.connectedCount(10L));
        assertEquals(4, events.getLast().revision());
        registry.disconnect("reconnected");
        assertEquals(4, events.getLast().revision());
        registry.sendSnapshot("reconnected", "room", "/topic/chat-rooms/10");
        assertEquals(5, events.size());
    }

    @Test
    void lastConnectionAloneStartsGraceAndThirtySecondBoundaryFreesTheSeat() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        Instant start = Instant.parse("2026-10-07T00:00:00Z");
        org.mockito.Mockito.when(clock.instant()).thenReturn(start);
        ChatPresenceRegistry registry = new ChatPresenceRegistry(clock);
        registry.connect(1L, 10L, 100L, 1, "first");
        registry.connect(1L, 10L, 100L, 1, "second");
        registry.disconnect("first");
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(100));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        assertTrue(registry.ownsConnection(1L, 100L, "second"));
        registry.disconnect("second");
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(129));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(130));
        assertTrue(registry.hasCapacity(2L, 10L, 1));
    }

    @Test
    void reservationRetryDoesNotExtendDeadlineAndReconnectChecksCapacityAfterExpiry() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        Instant start = Instant.parse("2026-10-07T00:00:00Z");
        org.mockito.Mockito.when(clock.instant()).thenReturn(start);
        ChatPresenceRegistry registry = new ChatPresenceRegistry(clock);
        registry.reserve(1L, 10L, 100L);
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(29));
        registry.reserve(1L, 10L, 100L);
        assertFalse(registry.hasCapacity(2L, 10L, 1));
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(30));
        assertTrue(registry.hasCapacity(2L, 10L, 1));
        registry.connect(2L, 10L, 200L, 1, "new-user");
        assertThrows(com.muse.meomuneum.chat.room.exception.ChatRoomException.class,
                () -> registry.connect(1L, 10L, 100L, 1, "late"));
    }

    @Test
    void reconnectDuringGraceRetainsOneSeatAndExplicitLeaveRevokesEveryConnection() {
        ChatPresenceRegistry registry = new ChatPresenceRegistry(Clock.systemUTC());
        registry.connect(1L, 10L, 100L, 1, "first");
        registry.disconnect("first");
        registry.connect(1L, 10L, 100L, 1, "reconnected");
        registry.connect(1L, 10L, 100L, 1, "other-device");
        assertEquals(Set.of("reconnected", "other-device"), registry.remove(1L, 100L));
        registry.reserve(1L, 10L, 101L);
        assertEquals(Set.of(), registry.remove(1L, 100L));
        assertFalse(registry.hasCapacity(2L, 10L, 1));
    }

    @Test
    void concurrentReservationsAndReconnectsNeverExceedTwentyFiveUsers() throws Exception {
        ChatPresenceRegistry registry = new ChatPresenceRegistry(Clock.fixed(Instant.now(), ZoneOffset.UTC));
        AtomicInteger admitted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(26)) {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (long user = 1; user <= 26; user++) {
                final long id = user;
                futures.add(executor.submit(() -> registry.exclusive(() -> {
                    if (registry.hasCapacity(id, 10L, 25)) {
                        registry.reserve(id, 10L, id);
                        registry.connect(id, 10L, id, 25, "session-" + id);
                        admitted.incrementAndGet();
                    }
                    return null;
                })));
            }
            for (var future : futures) {
                future.get();
            }
        }
        assertEquals(25, admitted.get());
        assertEquals(25, registry.connectedCount(10L));
    }
}
