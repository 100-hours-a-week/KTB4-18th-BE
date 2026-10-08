package com.muse.meomuneum.chat.connection;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

import com.muse.meomuneum.chat.message.service.ChatMessageDeliveryService;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;

@Component
public class ChatStompInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokens;
    private final ChatRoomEntryService entry;
    private final Clock clock;
    private final ChatSocketSessions sockets;
    private final ChatMessageDeliveryService delivery;
    private final Map<String, Identity> identities = new ConcurrentHashMap<>();

    public ChatStompInterceptor(JwtTokenProvider tokens, ChatRoomEntryService entry, Clock clock,
            ChatSocketSessions sockets, ChatMessageDeliveryService delivery) {
        this.tokens = tokens;
        this.entry = entry;
        this.clock = clock;
        this.sockets = sockets;
        this.delivery = delivery;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null || headers.getCommand() == StompCommand.DISCONNECT) {
            return message;
        }
        Map<String, Object> attributes = headers.getSessionAttributes();
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
            if (attributes.containsKey("chat.identity")) {
                throw new IllegalArgumentException("CHAT_MEMBERSHIP_REQUIRED");
            }
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new IllegalArgumentException("AUTH_REQUIRED");
            }
            TokenClaims claims = tokens.parseAccessToken(authorization.substring(7));
            Long roomId = positiveId(headers.getFirstNativeHeader("room_id"));
            Long membershipId = positiveId(headers.getFirstNativeHeader("membership_id"));
            if (claims.expiresAt() == null || !claims.expiresAt().isAfter(clock.instant())) {
                throw new IllegalArgumentException("AUTH_REQUIRED");
            }
            entry.connect(claims.userId(), roomId, membershipId, headers.getSessionId());
            Identity identity = new Identity(claims.userId(), claims.expiresAt(), roomId, membershipId);
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
        } else if (headers.getCommand() != StompCommand.UNSUBSCRIBE) {
            // Message sending is introduced by #141. Never allow direct broker publishing.
            throw new IllegalArgumentException("CHAT_SEND_UNAVAILABLE");
        }
        return message;
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        identities.remove(event.getSessionId());
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

    private record Identity(Long userId, Instant expiresAt, Long roomId, Long membershipId) {
    }
}
