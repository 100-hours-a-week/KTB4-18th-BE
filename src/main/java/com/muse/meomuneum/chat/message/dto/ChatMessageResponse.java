package com.muse.meomuneum.chat.message.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.muse.meomuneum.chat.message.domain.ChatRoomMessage;

public record ChatMessageResponse(@JsonProperty("message_id") Long messageId,
        @JsonProperty("client_message_id") String clientMessageId, @JsonProperty("room_id") Long roomId,
        @JsonProperty("user_id") Long userId, String nickname, String content,
        @JsonProperty("created_at") OffsetDateTime createdAt) {
    public static ChatMessageResponse from(ChatRoomMessage message) {
        return new ChatMessageResponse(message.getId(), message.getClientMessageId(), message.getChatRoom().getId(),
                message.getUser().getId(), message.getUser().getNickname(), message.getContent(),
                message.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
}
