package com.muse.meomuneum.chat.connection;

import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/** Prevents asynchronous publication from reaching a later join or an ended subscription. */
@Component
public class ChatMessageOutboundGuard implements ChannelInterceptor {
    private final ChatPresenceRegistry presence;

    public ChatMessageOutboundGuard(ChatPresenceRegistry presence) {
        this.presence = presence;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
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
