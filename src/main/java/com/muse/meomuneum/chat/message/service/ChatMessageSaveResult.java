package com.muse.meomuneum.chat.message.service;

import com.muse.meomuneum.chat.message.domain.ChatRoomMessage;

public record ChatMessageSaveResult(ChatRoomMessage message, boolean created) {
}
