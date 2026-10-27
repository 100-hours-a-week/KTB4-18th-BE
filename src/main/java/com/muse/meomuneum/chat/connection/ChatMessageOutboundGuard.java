package com.muse.meomuneum.chat.connection;

import java.time.Instant;
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
import com.muse.meomuneum.chat.participation.ChatParticipationService;

/** Prevents asynchronous publication from reaching a later join or an ended subscription. */
@Component
public class ChatMessageOutboundGuard implements ExecutorChannelInterceptor {
    private ChatParticipationService participation;

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
        return preSend(message, channel);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (coreVerifier != null
                && SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE) {
            var context = authSessions.context(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
            if (context.isEmpty()
                    || !authSessions.tokenValid(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()),
                            Instant.now())) {
                return null;
            }
            try {
                coreVerifier.assertValid(context.get().userId(), context.get().authSessionId());
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
        if (participation != null && message.getHeaders().get("chatMessageId") instanceof Long id) {
            String socket = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            var context = authSessions == null
                    ? Optional.<ChatAuthSessionRegistry.Context>empty()
                    : authSessions.context(socket);
            try {
                if (context.isEmpty() || !(message.getHeaders().get("chatRoomId") instanceof Long room)
                        || !participation.permitsMessage(context.get().userId(), socket, room, id)) {
                    return null;
                }
            } catch (RuntimeException unavailable) {
                participation.failClosed();
                return null;
            }
        }
        Object recipients = message.getHeaders().get("chatRecipients");
        Object roomId = message.getHeaders().get("chatRoomId");
        if (recipients instanceof Set<?> sessions && roomId instanceof Long room) {
            String session = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            if (!sessions.contains(session) || !presence.hasRoomSubscription(session, room)) {
                return null;
            }
        }
        return message;
    }
}
