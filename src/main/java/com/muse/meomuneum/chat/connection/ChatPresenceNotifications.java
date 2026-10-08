package com.muse.meomuneum.chat.connection;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.message.dto.ChatMessageEvent;

/** Snapshots and changes are serialized within the registry transition and versioned per room. */
@Component
public class ChatPresenceNotifications {
    private final ChatPresenceRegistry presence;
    private final ObjectProvider<MessageChannel> outbound;
    private final ObjectMapper mapper;

    public ChatPresenceNotifications(ChatPresenceRegistry presence,
            @Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> outbound, ObjectMapper mapper) {
        this.presence = presence;
        this.outbound = outbound;
        this.mapper = mapper;
    }

    @EventListener
    public void changed(ChatPresenceChangedEvent event) {
        Map<String, Set<String>> targets = event.sessionId() == null
                ? presence.roomSubscriptions(event.roomId())
                : Map.of(event.sessionId(), Set.of(event.subscriptionId()));
        try {
            byte[] body = mapper.writeValueAsBytes(new ChatMessageEvent("CHAT_PRESENCE", Map.of(
                    "room_id", event.roomId(), "connected_count", event.count(), "version", event.revision())));
            targets.forEach((session, subscriptions) -> subscriptions.forEach(subscription -> {
                SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                headers.setSessionId(session);
                headers.setSubscriptionId(subscription);
                headers.setDestination("/topic/chat-rooms/" + event.roomId());
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                headers.setHeader("chatRecipients", Set.of(session));
                headers.setHeader("chatRoomId", event.roomId());
                outbound.getObject().send(MessageBuilder.createMessage(body, headers.getMessageHeaders()));
            }));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("chat presence serialization failed");
        }
    }
}
