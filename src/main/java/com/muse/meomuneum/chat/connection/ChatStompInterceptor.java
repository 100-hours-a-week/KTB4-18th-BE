package com.muse.meomuneum.chat.connection;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.chat.message.service.ChatMessageDeliveryService;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;

@Component
public class ChatStompInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokens;
    private final ChatRoomEntryService entry;
    private final Clock clock;
    private final ChatSocketSessions sockets;
    private final ChatMessageDeliveryService delivery;
    private final ChatViewLifecycle views;
    private final ChatAuthSessionRegistry authSessions;
    private final AuthFlowService authFlowService;
    private final Map<String, Identity> identities = new ConcurrentHashMap<>();

    @Autowired
    public ChatStompInterceptor(JwtTokenProvider tokens, ChatRoomEntryService entry, Clock clock,
            ChatSocketSessions sockets, ChatMessageDeliveryService delivery, ChatViewLifecycle views,
            ChatAuthSessionRegistry authSessions, AuthFlowService authFlowService) {
        this.tokens = tokens;
        this.entry = entry;
        this.clock = clock;
        this.sockets = sockets;
        this.delivery = delivery;
        this.views = views;
        this.authSessions = authSessions;
        this.authFlowService = authFlowService;
    }

    public ChatStompInterceptor(JwtTokenProvider tokens, ChatRoomEntryService entry, Clock clock,
            ChatSocketSessions sockets, ChatMessageDeliveryService delivery, ChatViewLifecycle views,
            ChatAuthSessionRegistry authSessions) {
        this(tokens, entry, clock, sockets, delivery, views, authSessions, null);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null || headers.getCommand() == StompCommand.DISCONNECT) {
            return message;
        }
        Map<String, Object> attributes = headers.getSessionAttributes();
        if (attributes != null && headers.getCommand() != StompCommand.CONNECT && authFlowService != null) {
            Identity existing = (Identity) attributes.get("chat.identity");
            if (existing != null) {
                try {
                    authFlowService.assertActiveSession(existing.userId(), existing.authSessionId());
                } catch (RuntimeException revokedOrUnavailable) {
                    sockets.close(Set.of(headers.getSessionId()), 4102, "AUTH_REQUIRED");
                    return null;
                }
            }
        }
        if (headers.getCommand() == StompCommand.SEND) {
            Identity sender = attributes == null ? null : (Identity) attributes.get("chat.identity");
            if (sender == null || !sender.expiresAt().isAfter(clock.instant())) {
                sockets.close(Set.of(headers.getSessionId()), 4102, "AUTH_REQUIRED");
            } else {
                try {
                    delivery.send(sender.userId(), sender.roomId(), sender.membershipId(), headers.getSessionId(),
                            headers.getDestination(),
                            message.getPayload() instanceof byte[] bytes ? bytes : new byte[0]);
                } catch (RuntimeException unavailable) {
                    // Never let framework exception logging include a rejected SEND payload.
                    sockets.close(Set.of(headers.getSessionId()), 4103, "SEND_FAILED");
                }
            }
            return null;
        }
        if (attributes == null) {
            throw new IllegalArgumentException("AUTH_REQUIRED");
        }
        if (headers.getCommand() == StompCommand.CONNECT) {
            String authorization = headers.getFirstNativeHeader("Authorization");
            headers.removeNativeHeader("Authorization");
            String requestedAuthSession = headers.getFirstNativeHeader("auth_session_id");
            headers.removeNativeHeader("auth_session_id");
            if (attributes.containsKey("chat.identity")) {
                throw new IllegalArgumentException("CHAT_MEMBERSHIP_REQUIRED");
            }
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new IllegalArgumentException("AUTH_REQUIRED");
            }
            TokenClaims claims = tokens.parseAccessToken(authorization.substring(7));
            if (authFlowService != null) {
                authFlowService.assertActiveSession(claims.userId(), claims.sessionId());
            }
            ChatAuthSessionRegistry.Context authContext = authSessions.resolve(claims, requestedAuthSession);
            Long roomId = positiveId(headers.getFirstNativeHeader("room_id"));
            Long membershipId = positiveId(headers.getFirstNativeHeader("membership_id"));
            if (claims.expiresAt() == null || !claims.expiresAt().isAfter(clock.instant())) {
                throw new IllegalArgumentException("AUTH_REQUIRED");
            }
            String requestedViewId = headers.getFirstNativeHeader("view_session_id");
            headers.removeNativeHeader("view_session_id");
            String lastMessage = headers.getFirstNativeHeader("last_message_id");
            headers.removeNativeHeader("last_message_id");
            try {
                long lastId = lastMessage == null ? 0 : Long.parseLong(lastMessage);
                if (lastId < 0) {
                    throw new IllegalArgumentException("CHAT_QUERY_INVALID");
                }
                attributes.put("chat.last_message_id", lastId);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("CHAT_QUERY_INVALID");
            }
            entry.connect(claims.userId(), authContext.authSessionId(), roomId, membershipId, headers.getSessionId());
            try {
                views.connected(claims.userId(), roomId, membershipId, headers.getSessionId(),
                        requestedViewId);
            } catch (RuntimeException failure) {
                sockets.close(Set.of(headers.getSessionId()), 4103, "CHAT_VIEW_UNAVAILABLE");
                throw failure;
            }
            Identity identity = new Identity(claims.userId(), claims.sessionId(), claims.expiresAt(), roomId,
                    membershipId);
            authSessions.register(headers.getSessionId(), authContext);
            attributes.put("chat.identity", identity);
            identities.put(headers.getSessionId(), identity);
            headers.setUser((Principal) () -> claims.userId().toString());
            return message;
        }
        Identity identity = (Identity) attributes.get("chat.identity");
        if (identity == null || !identity.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("AUTH_REQUIRED");
        }
        entry.authorizeConnection(identity.userId(), identity.roomId(), identity.membershipId(),
                headers.getSessionId());
        if (headers.getCommand() == StompCommand.SUBSCRIBE) {
            String destination = headers.getDestination();
            if (!("/topic/chat-rooms/" + identity.roomId()).equals(destination)
                    && !"/user/queue/chat-status".equals(destination)
                    && !"/user/queue/chat-events".equals(destination)) {
                throw new IllegalArgumentException("CHAT_DESTINATION_DENIED");
            }
        } else if (headers.getCommand() == StompCommand.UNSUBSCRIBE) {
            // End reading before asynchronous broker unregistration can race a later message.
            views.unsubscribed(headers.getSessionId(), headers.getSubscriptionId());
        } else {
            // Message sending is introduced by #141. Never allow direct broker publishing.
            throw new IllegalArgumentException("CHAT_SEND_UNAVAILABLE");
        }
        return message;
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        identities.remove(event.getSessionId());
        authSessions.remove(event.getSessionId());
    }

    @Scheduled(fixedDelay = 10000)
    public void revalidateConnections() {
        identities.forEach((sessionId, identity) -> {
            if (!identity.expiresAt().isAfter(clock.instant())) {
                sockets.close(Set.of(sessionId), 4102, "AUTH_REQUIRED");
                return;
            }
            try {
                entry.authorizeConnection(identity.userId(), identity.roomId(), identity.membershipId(),
                        sessionId);
            } catch (ChatRoomException exception) {
                if (exception.getErrorCode() == ChatRoomErrorCode.CHAT_BANNED) {
                    entry.leaveAll(identity.userId(), 4101, "CHAT_BANNED|" + exception.getBannedUntil());
                } else {
                    sockets.close(Set.of(sessionId), 4100, "CHAT_LEFT");
                }
            }
        });
    }

    private Long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Do not echo client headers or credentials in STOMP errors.
        }
        throw new IllegalArgumentException("CHAT_MEMBERSHIP_REQUIRED");
    }

    private record Identity(Long userId, String authSessionId, Instant expiresAt, Long roomId, Long membershipId) {
    }
}
