package com.muse.meomuneum.chat.view;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;

/** One view per socket/tab; the existing presence monitor orders all lifecycle and message operations. */
@Component
public class ChatViewLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ChatViewLifecycle.class);
    private final ChatPresenceRegistry presence;
    private final ChatViewStorageService storage;
    private final Map<String, Connection> connections = new HashMap<>();

    public ChatViewLifecycle(ChatPresenceRegistry presence, ChatViewStorageService storage) {
        this.presence = presence;
        this.storage = storage;
    }

    public void connected(Long userId, Long roomId, Long membershipId, String socketId, String requestedViewId) {
        presence.exclusive(() -> {
            if (!presence.ownsConnection(userId, membershipId, socketId)) {
                throw denied();
            }
            storage.validateContext(userId, roomId, membershipId, requestedViewId);
            if (requestedViewId != null && connections.values().stream()
                    .anyMatch(connection -> requestedViewId.equals(connection.viewId))) {
                throw denied();
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
                connection.viewId = storage.subscribed(connection.userId, connection.roomId,
                        connection.membershipId, connection.viewId);
            }
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
                storage.unsubscribed(connection.viewId, connection.roomId);
            }
            return null;
        });
    }

    @EventListener
    public void disconnected(ChatViewConnectionClosedEvent event) {
        presence.exclusive(() -> {
            Connection connection = connections.remove(event.sessionId());
            if (connection != null && !connection.subscriptions.isEmpty()) {
                try {
                    storage.disconnected(connection.viewId);
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

    @Scheduled(fixedDelay = 5000)
    public void checkpointConnections() {
        try {
            presence.exclusive(() -> {
                connections.values().stream().filter(connection -> !connection.subscriptions.isEmpty())
                        .forEach(connection -> storage.checkpoint(connection.viewId, connection.roomId));
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
