package com.muse.meomuneum.chat.room.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatRoomJoinRequest(
        @JsonProperty("location_resolution_token") String locationResolutionToken) {
}
