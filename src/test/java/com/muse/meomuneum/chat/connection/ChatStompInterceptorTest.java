package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;

class ChatStompInterceptorTest {
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final ChatRoomEntryService entry = mock(ChatRoomEntryService.class);
    private final ChatStompInterceptor interceptor = new ChatStompInterceptor(jwt, entry,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(ChatSocketSessions.class),
            mock(com.muse.meomuneum.chat.message.service.ChatMessageDeliveryService.class));

    @Test
    void authenticatesThenAuthorizesEverySubscriptionAndRedactsTheAuthorizationHeader() {
        when(jwt.parseAccessToken("token")).thenReturn(new TokenClaims(7L, List.of("USER"), NOW.plusSeconds(60)));
        Map<String, Object> attributes = new HashMap<>();
        StompHeaderAccessor connect = headers(StompCommand.CONNECT, attributes);
        connect.setNativeHeader("Authorization", "Bearer token");
        connect.setNativeHeader("room_id", "700");
        connect.setNativeHeader("membership_id", "900");
        send(connect);
        assertEquals("7", connect.getUser().getName());
        assertNull(connect.getFirstNativeHeader("Authorization"));
        verify(entry).connect(7L, 700L, 900L, "session");

        StompHeaderAccessor subscribe = headers(StompCommand.SUBSCRIBE, attributes);
        subscribe.setDestination("/topic/chat-rooms/700");
        send(subscribe);
        verify(entry).authorizeConnection(7L, 700L, 900L, "session");
        subscribe.setDestination("/topic/chat-rooms/701");
        assertThrows(IllegalArgumentException.class, () -> send(subscribe));
        send(headers(StompCommand.SEND, attributes));
    }

    @Test
    void expiredOrMissingCredentialsAndUnconnectedSubscriptionsAreRejected() {
        when(jwt.parseAccessToken("expired")).thenReturn(new TokenClaims(7L, List.of(), NOW));
        StompHeaderAccessor expired = headers(StompCommand.CONNECT, new HashMap<>());
        expired.setNativeHeader("Authorization", "Bearer expired");
        expired.setNativeHeader("room_id", "700");
        expired.setNativeHeader("membership_id", "900");
        assertThrows(IllegalArgumentException.class, () -> send(expired));
        assertNull(expired.getFirstNativeHeader("Authorization"));
        assertThrows(IllegalArgumentException.class, () -> send(headers(StompCommand.CONNECT, new HashMap<>())));
        assertThrows(IllegalArgumentException.class, () -> send(headers(StompCommand.SUBSCRIBE, new HashMap<>())));
    }

    private StompHeaderAccessor headers(StompCommand command, Map<String, Object> attributes) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId("session");
        headers.setSessionAttributes(attributes);
        headers.setLeaveMutable(true);
        return headers;
    }

    private void send(StompHeaderAccessor headers) {
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null);
    }
}
