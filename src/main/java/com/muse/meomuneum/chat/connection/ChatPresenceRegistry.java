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
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.view.ChatViewConnectionClosedEvent;
import com.muse.meomuneum.chat.view.ChatViewMembershipEndedEvent;

/** Local transports and legacy seats; distributed participation uses the DB authority instead of this monitor. */
@Component
public class ChatPresenceRegistry {

    private ChatParticipationService participation;

    @Autowired(required = false)
    public void setParticipation(ChatParticipationService service) {
        participation = service;
    }

    private final Clock clock;
    private final ChatTransmissionPolicy transmission;
    private final ApplicationEventPublisher events;
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, Seat> seats = new HashMap<>();

    public ChatPresenceRegistry(Clock clock) {
        this(clock, new ChatTransmissionPolicy(clock,
                new ChatMessagePolicyProperties.Rules(null, true)), event -> {
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
        return seats.values().stream().anyMatch(seat -> seat.userId.equals(userId) && seat.roomId.equals(roomId))
                || seats.values().stream().filter(seat -> seat.roomId.equals(roomId))
                        .map(seat -> seat.userId).distinct().count() < capacity;
    }

    public synchronized void reserve(Long userId, Long roomId, Long membershipId) {
        Seat seat = seats.get(membershipId);
        if (seat != null) {
            if (!seat.userId.equals(userId) || !seat.roomId.equals(roomId)) {
                throw new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND);
            }
            return; // Retries must not indefinitely renew a disconnected reservation.
        }
        seats.put(membershipId, new Seat(userId, roomId, membershipId, clock.instant().plusSeconds(30)));
    }

    public synchronized void reserveTab(Long userId, Long roomId, Long membershipId, String viewId) {
        reserve(userId, roomId, membershipId);
        Seat seat = seats.get(membershipId);
        seat.tabs.putIfAbsent(viewId, clock.instant().plusSeconds(30));
        refreshExpiry(seat);
    }

    public synchronized void attachTab(Long membershipId, String viewId, String socketId) {
        Seat seat = seats.get(membershipId);
        if (seat == null || !seat.sessions.contains(socketId)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_VIEW_UNAVAILABLE);
        }
        seat.socketViews.put(socketId, viewId);
        seat.tabs.remove(viewId);
        seat.tabs.put(viewId, null);
        refreshExpiry(seat);
    }

    public synchronized Set<String> removeTab(Long userId, Long membershipId, String viewId) {
        Seat seat = seats.get(membershipId);
        if (seat == null || !seat.userId.equals(userId)) {
            return Set.of();
        }
        int previousCount = connectedCount(seat.roomId);
        Set<String> ended = new HashSet<>();
        seat.socketViews.forEach((socket, view) -> {
            if (viewId.equals(view)) {
                ended.add(socket);
            }
        });
        ended.forEach(socket -> {
            seat.sessions.remove(socket);
            seat.subscriptions.remove(socket);
            seat.socketViews.remove(socket);
        });
        seat.tabs.remove(viewId);
        if (seat.sessions.isEmpty() && seat.tabs.isEmpty()) {
            seats.remove(membershipId);
            transmission.ended(membershipId);
        } else {
            refreshExpiry(seat);
        }
        changed(seat.roomId, previousCount);
        return Set.copyOf(ended);
    }

    public synchronized boolean hasParticipation(Long membershipId) {
        return seats.containsKey(membershipId);
    }

    private void refreshExpiry(Seat seat) {
        if (!seat.sessions.isEmpty()) {
            seat.expiresAt = null;
        } else if (!seat.tabs.isEmpty()) {
            seat.expiresAt = seat.tabs.values().stream().filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo).orElse(clock.instant());
        }
    }

    public synchronized void connect(Long userId, Long roomId, Long membershipId, int capacity, String sessionId) {
        expire();
        if (participation == null && !hasCapacity(userId, roomId, capacity)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED);
        }
        reserve(userId, roomId, membershipId);
        Seat seat = seats.get(membershipId);
        int previousCount = connectedCount(roomId);
        seat.sessions.add(sessionId);
        seat.expiresAt = null;
        changed(roomId, previousCount);
    }

    public synchronized void disconnect(String sessionId) {
        events.publishEvent(new ChatViewConnectionClosedEvent(sessionId));
        for (Seat seat : seats.values()) {
            int previousCount = connectedCount(seat.roomId);
            seat.subscriptions.remove(sessionId);
            String viewId = seat.socketViews.remove(sessionId);
            if (viewId != null) {
                seat.tabs.put(viewId, clock.instant().plusSeconds(30));
            }
            if (seat.sessions.remove(sessionId) && seat.sessions.isEmpty()) {
                seat.expiresAt = clock.instant().plusSeconds(30);
            }
            refreshExpiry(seat);
            changed(seat.roomId, previousCount);
        }
    }

    public synchronized boolean ownsConnection(Long userId, Long membershipId, String sessionId) {
        Seat seat = seats.get(membershipId);
        return seat != null && seat.userId.equals(userId) && seat.sessions.contains(sessionId)
                && (participation == null || participation.socketValid(userId, membershipId, sessionId));
    }

    public synchronized Set<String> remove(Long userId, Long membershipId) {
        Seat seat = seats.get(membershipId);
        if (seat != null && !seat.userId.equals(userId)) {
            return Set.of();
        }
        events.publishEvent(new ChatViewMembershipEndedEvent(membershipId));
        transmission.ended(membershipId);
        if (seat == null) {
            return Set.of();
        }
        int previousCount = connectedCount(seat.roomId);
        seats.remove(membershipId);
        changed(seat.roomId, previousCount);
        return Set.copyOf(seat.sessions);
    }

    public synchronized void expire() {
        Instant now = clock.instant();
        seats.values().forEach(seat -> seat.tabs.values()
                .removeIf(expiry -> expiry != null && !expiry.isAfter(now)));
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
                        .contains("/topic/chat-rooms/" + roomId)
                        && (participation == null || participation.socketValid(seat.userId,
                                seat.membershipId, sessionId)));
    }

    public synchronized int connectedCount(Long roomId) {
        if (participation != null) {
            return participation.connectedCount(roomId);
        }
        return (int) seats.values().stream().filter(seat -> seat.roomId.equals(roomId) && !seat.sessions.isEmpty())
                .map(seat -> seat.userId).distinct().count();
    }

    private void changed(Long roomId, int previousCount) {
        if (participation != null) {
            return; // Only committed DB room versions may describe global presence.
        }
        int count = connectedCount(roomId);
        if (previousCount != count) {
            revisions.merge(roomId, 1L, Long::sum);
            events.publishEvent(new ChatPresenceChangedEvent(roomId, count, revisions.get(roomId), null, null));
        }
    }

    public synchronized void sendSnapshot(String sessionId, String subscriptionId, String destination) {
        if (participation != null) {
            seats.values().stream().filter(seat -> seat.sessions.contains(sessionId)
                    && destination.equals("/topic/chat-rooms/" + seat.roomId)).forEach(seat -> {
                        var snapshot = participation.snapshot(seat.roomId);
                        events.publishEvent(new ChatPresenceChangedEvent(seat.roomId, snapshot.connected(),
                                snapshot.version(), sessionId, subscriptionId));
                    });
            return;
        }
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
        private final Long userId;
        private final Long roomId;
        private final Long membershipId;
        private final Set<String> sessions = new HashSet<>();
        private final Map<String, Map<String, String>> subscriptions = new HashMap<>();
        private final Map<String, Instant> tabs = new HashMap<>();
        private final Map<String, String> socketViews = new HashMap<>();
        private Instant expiresAt;

        private Seat(Long userId, Long roomId, Long membershipId, Instant expiresAt) {
            this.userId = userId;
            this.roomId = roomId;
            this.membershipId = membershipId;
            this.expiresAt = expiresAt;
        }
    }
}
