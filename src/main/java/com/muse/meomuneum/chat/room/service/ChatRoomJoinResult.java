package com.muse.meomuneum.chat.room.service;

import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;

public record ChatRoomJoinResult(ChatRoomMembershipResponse membership, boolean created) {
}
