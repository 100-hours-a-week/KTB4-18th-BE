package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

class ChatMessageOutboundGuardTest {
    @Test
    void dropsQueuedEventsForNewOrDepartedSubscribers() {
        ChatPresenceRegistry presence = new ChatPresenceRegistry(Clock.systemUTC());
        ChatMessageOutboundGuard guard = new ChatMessageOutboundGuard(presence);
        presence.connect(1L, 10L, 100L, 25, "first");
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        Set<String> acceptedRecipients = presence.recipients(10L);
        presence.connect(2L, 10L, 200L, 25, "later");
        presence.subscribed("later", "room", "/topic/chat-rooms/10");
        Message<byte[]> first = outgoing("first", acceptedRecipients);
        assertSame(first, guard.preSend(first, null));
        assertNull(guard.preSend(outgoing("later", acceptedRecipients), null));
        presence.unsubscribed("first", "room");
        assertNull(guard.preSend(first, null));
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        presence.remove(1L, 100L);
        assertNull(guard.preSend(first, null));
    }

    private Message<byte[]> outgoing(String session, Set<String> recipients) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(session);
        headers.setHeader("chatRecipients", recipients);
        headers.setHeader("chatRoomId", 10L);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
