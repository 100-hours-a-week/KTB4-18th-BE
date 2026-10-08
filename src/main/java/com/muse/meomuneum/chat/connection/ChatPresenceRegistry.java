package com.muse.meomuneum.chat.connection;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.message.policy.ChatMessagePolicyProperties;
import com.muse.meomuneum.chat.message.policy.ChatTransmissionPolicy;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;

/** Single-server seats. All database transitions must finish inside exclusive(). */
@Component
public class ChatPresenceRegistry {

    private final Clock clock;
    private final ChatTransmissionPolicy transmission;
    private final ApplicationEventPublisher events;
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, Seat> seats = new HashMap<>();

    public ChatPresenceRegistry(Clock clock) {
        this(clock, new ChatTransmissionPolicy(clock,
                new ChatMessagePolicyProperties.Rules(null, true, true, null, null)), event -> {
                });
    }

    @Autowired
    public ChatPresenceRegistry(Clock clock,
            ChatTransmissionPolicy transmission, ApplicationEventPublisher events) {
        this.clock = clock;
        this.transmission = transmission;
        this.events = events;
    }

    public synchronized <T> T exclusive(Supplier<T> work) {
        expire();
        return work.get();
    }

    public synchronized boolean hasCapacity(Long userId, Long roomId, int capacity) {
        expire();
        Seat own = seats.get(userId);
        return own != null && own.roomId.equals(roomId)
                || seats.values().stream().filter(seat -> seat.roomId.equals(roomId)).count() < capacity;
    }

    public synchronized void reserve(Long userId, Long roomId, Long membershipId) {
        Seat seat = seats.get(userId);
        if (seat != null && seat.membershipId.equals(membershipId)) {
            return; // Retries must not indefinitely renew a disconnected reservation.
        }
        seats.put(userId, new Seat(roomId, membershipId, clock.instant().plusSeconds(30)));
    }

    public synchronized void connect(Long userId, Long roomId, Long membershipId, int capacity, String sessionId) {
        expire();
        if (!hasCapacity(userId, roomId, capacity)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED);
        }
        reserve(userId, roomId, membershipId);
        Seat seat = seats.get(userId);
        int previousCount = connectedCount(roomId);
        seat.sessions.add(sessionId);
        seat.expiresAt = null;
        changed(roomId, previousCount);
    }

    public synchronized void disconnect(String sessionId) {
        for (Seat seat : seats.values()) {
            int previousCount = connectedCount(seat.roomId);
            seat.subscriptions.remove(sessionId);
            if (seat.sessions.remove(sessionId) && seat.sessions.isEmpty()) {
                seat.expiresAt = clock.instant().plusSeconds(30);
            }
            changed(seat.roomId, previousCount);
        }
    }

    public synchronized boolean ownsConnection(Long userId, Long membershipId, String sessionId) {
        Seat seat = seats.get(userId);
        return seat != null && seat.membershipId.equals(membershipId) && seat.sessions.contains(sessionId);
    }

    public synchronized Set<String> remove(Long userId, Long membershipId) {
        transmission.ended(membershipId);
        Seat seat = seats.get(userId);
        if (seat == null || !seat.membershipId.equals(membershipId)) {
            return Set.of();
        }
        int previousCount = connectedCount(seat.roomId);
        seats.remove(userId);
        changed(seat.roomId, previousCount);
        return Set.copyOf(seat.sessions);
    }

    public synchronized void expire() {
        Instant now = clock.instant();
        seats.values().removeIf(seat -> seat.expiresAt != null && !seat.expiresAt.isAfter(now));
    }

    public synchronized void subscribed(String sessionId, String subscriptionId, String destination) {
        seats.values().stream().filter(seat -> seat.sessions.contains(sessionId))
                .forEach(seat -> seat.subscriptions.computeIfAbsent(sessionId, ignored -> new HashMap<>())
                        .put(subscriptionId, destination));
    }

    public synchronized void unsubscribed(String sessionId, String subscriptionId) {
        seats.values().forEach(seat -> seat.subscriptions.getOrDefault(sessionId, new HashMap<>())
                .remove(subscriptionId));
    }

    public synchronized Set<String> recipients(Long roomId) {
        Set<String> result = new HashSet<>();
        seats.values().stream().filter(seat -> seat.roomId.equals(roomId)).forEach(seat -> seat.sessions.stream()
                .filter(session -> hasRoomSubscription(session, roomId)).forEach(result::add));
        return Set.copyOf(result);
    }

    public synchronized boolean hasRoomSubscription(String sessionId, Long roomId) {
        return seats.values().stream().filter(seat -> seat.roomId.equals(roomId) && seat.sessions.contains(sessionId))
                .anyMatch(seat -> seat.subscriptions.getOrDefault(sessionId, Map.of()).values()
                        .contains("/topic/chat-rooms/" + roomId));
    }

    public synchronized int connectedCount(Long roomId) {
        return (int) seats.values().stream().filter(seat -> seat.roomId.equals(roomId) && !seat.sessions.isEmpty())
                .count();
    }

    private void changed(Long roomId, int previousCount) {
        int count = connectedCount(roomId);
        if (previousCount != count) {
            revisions.merge(roomId, 1L, Long::sum);
            events.publishEvent(new ChatPresenceChangedEvent(roomId, count, revisions.get(roomId), null, null));
        }
    }

    public synchronized void sendSnapshot(String sessionId, String subscriptionId, String destination) {
        seats.values().stream().filter(seat -> seat.sessions.contains(sessionId)
                && destination.equals("/topic/chat-rooms/" + seat.roomId))
                .forEach(seat -> events
                        .publishEvent(new ChatPresenceChangedEvent(seat.roomId, connectedCount(seat.roomId),
                                revisions.getOrDefault(seat.roomId, 0L), sessionId, subscriptionId)));
    }

    public synchronized Map<String, Set<String>> roomSubscriptions(Long roomId) {
        Map<String, Set<String>> result = new HashMap<>();
        seats.values().stream().filter(seat -> seat.roomId.equals(roomId))
                .forEach(seat -> seat.subscriptions.forEach((session, subscriptions) -> {
                    Set<String> ids = new HashSet<>();
                    subscriptions.forEach((id, destination) -> {
                        if (destination.equals("/topic/chat-rooms/" + roomId)) {
                            ids.add(id);
                        }
                    });
                    if (!ids.isEmpty()) {
                        result.put(session, Set.copyOf(ids));
                    }
                }));
        return Map.copyOf(result);
    }

    private static final class Seat {
        private final Long roomId;
        private final Long membershipId;
        private final Set<String> sessions = new HashSet<>();
        private final Map<String, Map<String, String>> subscriptions = new HashMap<>();
        private Instant expiresAt;

        private Seat(Long roomId, Long membershipId, Instant expiresAt) {
            this.roomId = roomId;
            this.membershipId = membershipId;
            this.expiresAt = expiresAt;
        }
    }
}
