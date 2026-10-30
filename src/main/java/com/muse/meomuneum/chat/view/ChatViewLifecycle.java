package com.muse.meomuneum.chat.view;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;

/** Local socket/subscription registry; optional DB transitions provide cross-Task serialization. */
@Component
public class ChatViewLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ChatViewLifecycle.class);
    private ChatParticipationService participation;
    private ChatAuthSessionRegistry authSessions;

    @Autowired(required = false)
    public void setAuthSessions(ChatAuthSessionRegistry registry) {
        authSessions = registry;
    }

    @Autowired(required = false)
    public void setParticipation(ChatParticipationService service) {
        participation = service;
    }

    private final ChatPresenceRegistry presence;
    private final ChatViewStorageService storage;
    private final Map<String, Connection> connections = new HashMap<>();

    public ChatViewLifecycle(ChatPresenceRegistry presence, ChatViewStorageService storage) {
        this.presence = presence;
        this.storage = storage;
    }

    public void connected(Long userId, Long roomId, Long membershipId, String socketId, String requestedViewId) {
        connected(userId, roomId, membershipId, socketId, requestedViewId, null);
    }

    public void connected(Long userId, Long roomId, Long membershipId, String socketId, String requestedViewId,
            String authSessionId) {
        presence.exclusive(() -> {
            if (participation != null) {
                participation.claim(userId, authSessionId, roomId, membershipId, requestedViewId, socketId);
                presence.connect(userId, roomId, membershipId, Integer.MAX_VALUE, socketId);
            }
            if (!presence.ownsConnection(userId, membershipId, socketId)) {
                throw denied();
            }
            if (participation == null) {
                storage.validateContext(userId, roomId, membershipId, requestedViewId);
            }
            if (requestedViewId != null && connections.values().stream()
                    .anyMatch(connection -> requestedViewId.equals(connection.viewId))) {
                throw denied();
            }
            if (requestedViewId != null) {
                presence.attachTab(membershipId, requestedViewId, socketId);
            }
            connections.put(socketId, new Connection(userId, roomId, membershipId, requestedViewId));
            return null;
        });
    }

    public String subscribed(String socketId, String subscriptionId, String destination) {
        return presence.exclusive(() -> {
            Connection connection = connections.get(socketId);
            if (connection == null || !destination.equals("/topic/chat-rooms/" + connection.roomId)) {
                return null;
            }
            if (!presence.ownsConnection(connection.userId, connection.membershipId, socketId)
                    || subscriptionId == null) {
                throw denied();
            }
            if (connection.subscriptions.isEmpty()) {
                connection.viewId = participation == null
                        ? storage.subscribed(connection.userId, connection.roomId, connection.membershipId,
                                connection.viewId)
                        : participation.ready(connection.userId, connection.roomId, connection.membershipId,
                                connection.viewId, socketId);
            }
            presence.attachTab(connection.membershipId, connection.viewId, socketId);
            connection.subscriptions.add(subscriptionId);
            return connection.viewId;
        });
    }

    public void unsubscribed(String socketId, String subscriptionId) {
        presence.exclusive(() -> {
            presence.unsubscribed(socketId, subscriptionId);
            Connection connection = connections.get(socketId);
            if (connection != null && connection.subscriptions.remove(subscriptionId)
                    && connection.subscriptions.isEmpty()) {
                if (participation == null) {
                    storage.unsubscribed(connection.viewId, connection.roomId);
                } else {
                    participation.paused(connection.userId, connection.roomId, connection.membershipId,
                            connection.viewId, socketId);
                }
            }
            return null;
        });
    }

    public Departure leave(Long userId, Long roomId, Long membershipId, String viewId) {
        return presence.exclusive(() -> {
            boolean lastView = storage.leave(userId, roomId, membershipId, viewId);
            Set<String> ended = presence.removeTab(userId, membershipId, viewId);
            ended.forEach(connections::remove);
            return new Departure(ended, lastView);
        });
    }

    public record Departure(Set<String> sockets, boolean lastView) {
    }

    @EventListener
    public void disconnected(ChatViewConnectionClosedEvent event) {
        presence.exclusive(() -> {
            Connection connection = connections.remove(event.sessionId());
            if (connection != null && (participation != null || !connection.subscriptions.isEmpty())) {
                try {
                    if (participation == null) {
                        storage.disconnected(connection.viewId);
                    } else {
                        participation.disconnected(connection.userId, connection.roomId, connection.membershipId,
                                connection.viewId, event.sessionId());
                    }
                } catch (RuntimeException failure) {
                    // Runtime permission is already revoked. An unrecorded gap must not reopen on reconnect.
                    log.warn("event=chat_view_disconnect_failed exception={}", failure.getClass().getSimpleName());
                }
            }
            return null;
        });
    }

    @EventListener
    public void ended(ChatViewMembershipEndedEvent event) {
        presence.exclusive(() -> {
            connections.values().removeIf(connection -> connection.membershipId.equals(event.membershipId()));
            try {
                storage.ended(event.membershipId());
            } catch (RuntimeException failure) {
                // The committed inactive membership denies all recovery, even if range finalization fails.
                log.warn("event=chat_view_end_failed exception={}", failure.getClass().getSimpleName());
            }
            return null;
        });
    }

    /** For #145: a durable view alone is insufficient; require its current live subscription as well. */
    public Context context(String socketId) {
        return presence.exclusive(() -> {
            Connection connection = connections.get(socketId);
            if (connection == null || connection.subscriptions.isEmpty()) {
                throw denied();
            }
            return new Context(connection.userId, connection.roomId, connection.membershipId);
        });
    }

    public record Context(Long userId, Long roomId, Long membershipId) {
    }

    public record LocalView(long userId, long roomId, long membershipId, String viewId) {
    }

    public Map<String, LocalView> localViews() {
        return presence.exclusive(() -> {
            Map<String, LocalView> snapshot = new HashMap<>();
            connections.forEach((socket, connection) -> {
                if (connection.viewId != null) {
                    snapshot.put(socket, new LocalView(connection.userId, connection.roomId,
                            connection.membershipId, connection.viewId));
                }
            });
            return Map.copyOf(snapshot);
        });
    }

    public Set<String> discardInvalidSocket(String socket, LocalView expected) {
        return presence.exclusive(() -> {
            Connection connection = connections.get(socket);
            if (connection == null || connection.userId != expected.userId() || connection.roomId != expected.roomId()
                    || connection.membershipId != expected.membershipId()
                    || !expected.viewId().equals(connection.viewId)
                    || participation != null
                            && participation.socketValid(expected.userId(), expected.membershipId(), socket)) {
                return Set.of();
            }
            connections.remove(socket);
            if (participation != null) {
                participation.forgetSocket(socket);
            }
            if (authSessions != null) {
                authSessions.remove(socket);
            }
            presence.removeTab(expected.userId(), expected.membershipId(), expected.viewId());
            return Set.of(socket);
        });
    }

    /** DB terminal state was verified by the caller. Remove local metadata before closing transports. */
    public Set<String> discardEnded(long user, long room, long member, String view) {
        return presence.exclusive(() -> {
            Set<String> removed = new HashSet<>();
            connections.entrySet().removeIf(entry -> {
                Connection connection = entry.getValue();
                boolean matches = connection.userId == user && connection.roomId == room
                        && connection.membershipId == member && (view == null || view.equals(connection.viewId));
                if (matches) {
                    if (participation != null) {
                        participation.forgetSocket(entry.getKey());
                    }
                    if (authSessions != null) {
                        authSessions.remove(entry.getKey());
                    }
                    presence.removeTab(user, member, connection.viewId);
                    removed.add(entry.getKey());
                }
                return matches;
            });
            return Set.copyOf(removed);
        });
    }

    public List<ChatViewInterval> authorize(Long userId, Long roomId, Long membershipId, String viewId) {
        return presence.exclusive(() -> {
            boolean subscribed = viewId != null && connections.entrySet().stream().anyMatch(entry -> {
                Connection connection = entry.getValue();
                return viewId.equals(connection.viewId) && userId.equals(connection.userId)
                        && roomId.equals(connection.roomId) && membershipId.equals(connection.membershipId)
                        && !connection.subscriptions.isEmpty()
                        && presence.ownsConnection(userId, membershipId, entry.getKey());
            });
            if (!subscribed) {
                throw denied();
            }
            return storage.authorizedIntervals(userId, roomId, membershipId, viewId);
        });
    }

    /** B8 history scope; reconnect/recovery authorization remains bound to its original tab. */
    public ChatAccountReadIntervals authorizeAccount(Long userId, Long roomId, Long membershipId, String viewId) {
        return presence.exclusive(() -> {
            authorize(userId, roomId, membershipId, viewId);
            Set<String> activeViews = new HashSet<>();
            connections.forEach((socket, connection) -> {
                if (userId.equals(connection.userId) && roomId.equals(connection.roomId)
                        && !connection.subscriptions.isEmpty()
                        && presence.ownsConnection(userId, connection.membershipId, socket)
                        && !viewId.equals(connection.viewId)) {
                    activeViews.add(connection.viewId);
                }
            });
            if (participation == null) {
                activeViews.forEach(active -> storage.checkpoint(active, roomId));
            }
            return storage.authorizedAccountIntervals(userId, roomId, membershipId, viewId);
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void checkpointConnections() {
        try {
            presence.exclusive(() -> {
                connections.forEach((socket, connection) -> {
                    if (!connection.subscriptions.isEmpty()) {
                        if (participation == null) {
                            storage.checkpoint(connection.viewId, connection.roomId);
                        } else {
                            participation.checkpoint(connection.userId, connection.roomId, connection.membershipId,
                                    connection.viewId, socket);
                        }
                    }
                });
                return null;
            });
        } catch (RuntimeException failure) {
            log.warn("event=chat_view_checkpoint_failed exception={}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void purgeExpiredRecords() {
        try {
            presence.exclusive(storage::deleteExpiredRecords);
        } catch (RuntimeException failure) {
            log.warn("event=chat_view_cleanup_failed exception={}", failure.getClass().getSimpleName());
        }
    }

    private ChatRoomException denied() {
        return new ChatRoomException(ChatRoomErrorCode.CHAT_VIEW_UNAVAILABLE);
    }

    private static final class Connection {
        private final Long userId;
        private final Long roomId;
        private final Long membershipId;
        private final Set<String> subscriptions = new HashSet<>();
        private String viewId;

        private Connection(Long userId, Long roomId, Long membershipId, String viewId) {
            this.userId = userId;
            this.roomId = roomId;
            this.membershipId = membershipId;
            this.viewId = viewId;
        }
    }
}
