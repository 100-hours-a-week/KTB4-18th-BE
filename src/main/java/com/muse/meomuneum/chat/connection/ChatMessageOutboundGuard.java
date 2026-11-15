package com.muse.meomuneum.chat.connection;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.message.relay.ChatDeliveryMetrics;
import com.muse.meomuneum.chat.participation.ChatParticipationService;

/** Prevents asynchronous publication from reaching a later join or an ended subscription. */
@Component
public class ChatMessageOutboundGuard implements ExecutorChannelInterceptor {
    private ChatParticipationService participation;
    private ChatDeliveryMetrics deliveryMetrics;
    private ChatMessagePermissionBatcher permissionBatcher;

    @Autowired(required = false)
    public void setPermissionBatcher(ChatMessagePermissionBatcher batcher) {
        permissionBatcher = batcher;
    }
    private final ThreadLocal<ArrayDeque<Long>> handlerStarts = new ThreadLocal<>();

    @Autowired(required = false)
    public void setDeliveryMetrics(ChatDeliveryMetrics value) {
        deliveryMetrics = value;
    }

    @Autowired(required = false)
    public void setParticipation(ChatParticipationService service) {
        participation = service;
    }

    private final ChatPresenceRegistry presence;
    private ChatSessionVerifier coreVerifier;
    private ChatAuthSessionRegistry authSessions;

    public ChatMessageOutboundGuard(ChatPresenceRegistry presence) {
        this.presence = presence;
    }

    @Autowired(required = false)
    public void setCoreVerifier(ChatSessionVerifier verifier, ChatAuthSessionRegistry registry) {
        coreVerifier = verifier;
        authSessions = registry;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        Message<?> allowed = timedCheck(message, true);
        if (allowed != null && deliveryMetrics != null
                && SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE) {
            var starts = handlerStarts.get();
            if (starts == null) {
                starts = new ArrayDeque<>();
                handlerStarts.set(starts);
            }
            starts.push(System.nanoTime());
        }
        return allowed;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception failure) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return;
        }
        var starts = handlerStarts.get();
        if (starts != null && !starts.isEmpty()) {
            ChatDeliveryMetrics.record(deliveryMetrics, ChatDeliveryMetrics.Stage.OUTBOUND_HANDLER, starts.pop());
            if (starts.isEmpty()) {
                handlerStarts.remove();
            }
        }
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return timedCheck(message, false);
    }

    private Message<?> timedCheck(Message<?> message, boolean handling) {
        long started = System.nanoTime();
        try {
            return check(message, handling);
        } finally {
            if (message.getHeaders().get("chatMessageId") instanceof Long) {
                ChatDeliveryMetrics.record(deliveryMetrics, handling
                        ? ChatDeliveryMetrics.Stage.MESSAGE_HANDLE_GUARD
                        : ChatDeliveryMetrics.Stage.MESSAGE_ENQUEUE_GUARD, started);
            } else if (message.getHeaders().get("chatRecipients") instanceof Set<?>) {
                ChatDeliveryMetrics.record(deliveryMetrics, handling
                        ? ChatDeliveryMetrics.Stage.PRESENCE_HANDLE_GUARD
                        : ChatDeliveryMetrics.Stage.PRESENCE_ENQUEUE_GUARD, started);
            }
        }
    }

    private Message<?> check(Message<?> message, boolean handling) {
        Object initialRecipients = message.getHeaders().get("chatRecipients");
        if (initialRecipients instanceof Set<?> sessions
                && !sessions.contains(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))) {
            return null;
        }
        if (!coreValid(message)) {
            return null;
        }
        Object recipients = message.getHeaders().get("chatRecipients");
        Object roomId = message.getHeaders().get("chatRoomId");
        Object messageId = message.getHeaders().get("chatMessageId");
        if (participation != null && (messageId instanceof Long || recipients instanceof Set<?>)) {
            String socket = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            var context = authSessions == null
                    ? Optional.<ChatAuthSessionRegistry.Context>empty()
                    : authSessions.context(socket);
            long started = System.nanoTime();
            try {
                if (context.isEmpty() || !(roomId instanceof Long room)) {
                    return null;
                }
                boolean permitted = messageId instanceof Long id
                        ? handling
                                ? permissionBatcher == null
                                        ? participation.permitsMessage(context.get().userId(), socket, room, id)
                                        : permissionBatcher.permitsMessage(context.get().userId(), socket, room, id)
                                : participation.permitsMessageEnqueue(context.get().userId(), socket, room, id,
                                        message.getHeaders().get("chatEnqueuePermissions"))
                        : handling
                                ? permitsSocketAtHandler(context.get(), socket, room,
                                        message.getHeaders().get("chatMusicVersion") instanceof Long)
                                : participation.permitsSocketEnqueue(context.get().userId(), socket, room,
                                        message.getHeaders().get("chatSocketEnqueuePermissions"));
                if (!permitted) {
                    return null;
                }
            } catch (RuntimeException unavailable) {
                participation.failClosed("outbound_permission", unavailable, started);
                return null;
            }
        }
        if (recipients instanceof Set<?> sessions && roomId instanceof Long room) {
            String session = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            boolean subscribed = participation == null
                    ? presence.hasRoomSubscription(session, room)
                    : presence.hasLocalRoomSubscription(session, room);
            if (!sessions.contains(session) || !subscribed) {
                return null;
            }
        }
        if (handling && participation != null && (messageId instanceof Long || recipients instanceof Set<?>)
                && (!participation.available() || !coreValid(message))) {
            return null;
        }
        return message;
    }

    private boolean permitsSocketAtHandler(ChatAuthSessionRegistry.Context context, String socket, long room,
            boolean music) {
        if (permissionBatcher != null) {
            return music
                    ? permissionBatcher.permitsMusic(context.userId(), socket, room)
                    : permissionBatcher.permitsSocket(context.userId(), socket, room);
        }
        return music
                ? participation.permitsMusicBatch(java.util.List.of(
                        new ChatParticipationService.MessagePermission(context.userId(), socket, room, -1))).getFirst()
                : participation.permitsSocket(context.userId(), socket, room);
    }

    private boolean coreValid(Message<?> message) {
        if (coreVerifier == null
                || SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return true;
        }
        String socket = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        var context = authSessions.context(socket);
        if (context.isEmpty() || !authSessions.tokenValid(socket, Instant.now())) {
            return false;
        }
        try {
            coreVerifier.assertValid(context.get().userId(), context.get().authSessionId());
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
