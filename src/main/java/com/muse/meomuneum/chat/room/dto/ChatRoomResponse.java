package com.muse.meomuneum.chat.room.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.muse.meomuneum.chat.room.domain.ChatRoom;

public record ChatRoomResponse(
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("region_id") Long regionId,
        @JsonProperty("region_name") String regionName,
        int capacity,
        String status) {

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return new ChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getRegion().getId(),
                chatRoom.getRegion().getName(),
                chatRoom.getCapacity(),
                chatRoom.getStatus().name()
        );
    }
}
