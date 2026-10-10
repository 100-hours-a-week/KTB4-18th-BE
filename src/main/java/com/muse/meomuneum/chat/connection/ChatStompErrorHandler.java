package com.muse.meomuneum.chat.connection;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import com.muse.meomuneum.chat.room.exception.ChatRoomException;

public class ChatStompErrorHandler extends StompSubProtocolErrorHandler {
    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable exception) {
        String code = "AUTH_REQUIRED";
        java.time.OffsetDateTime bannedUntil = null;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ChatRoomException chatException) {
                bannedUntil = chatException.getBannedUntil();
                code = switch (chatException.getErrorCode()) {
                    case CHAT_BANNED -> "CHAT_BANNED";
                    case CHAT_ROOM_CAPACITY_EXCEEDED -> "CHAT_FULL";
                    default -> "CHAT_LEFT";
                };
                break;
            }
        }
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setMessage(code);
        if (bannedUntil != null) {
            headers.setNativeHeader("banned_until", bannedUntil.toString());
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
