package com.muse.meomuneum.chat.connection;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;

/** Single-server seats. All database transitions must finish inside exclusive(). */
@Component
public class ChatPresenceRegistry {

    private final Clock clock;
    private final Map<Long, Seat> seats = new HashMap<>();

    public ChatPresenceRegistry(Clock clock) {
        this.clock = clock;
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
        seat.sessions.add(sessionId);
        seat.expiresAt = null;
    }

    public synchronized void disconnect(String sessionId) {
        for (Seat seat : seats.values()) {
            if (seat.sessions.remove(sessionId) && seat.sessions.isEmpty()) {
                seat.expiresAt = clock.instant().plusSeconds(30);
            }
        }
    }

    public synchronized boolean ownsConnection(Long userId, Long membershipId, String sessionId) {
        Seat seat = seats.get(userId);
        return seat != null && seat.membershipId.equals(membershipId) && seat.sessions.contains(sessionId);
    }

    public synchronized Set<String> remove(Long userId, Long membershipId) {
        Seat seat = seats.get(userId);
        if (seat == null || !seat.membershipId.equals(membershipId)) {
            return Set.of();
        }
        seats.remove(userId);
        return Set.copyOf(seat.sessions);
    }

    public synchronized void expire() {
        Instant now = clock.instant();
        seats.values().removeIf(seat -> seat.expiresAt != null && !seat.expiresAt.isAfter(now));
    }

    private static final class Seat {
        private final Long roomId;
        private final Long membershipId;
        private final Set<String> sessions = new HashSet<>();
        private Instant expiresAt;

        private Seat(Long roomId, Long membershipId, Instant expiresAt) {
            this.roomId = roomId;
            this.membershipId = membershipId;
            this.expiresAt = expiresAt;
        }
    }
}
