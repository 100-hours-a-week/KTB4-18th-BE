package com.muse.meomuneum.chat.room.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;

public record ChatRoomMembershipResponse(
        @JsonProperty("membership_id") Long membershipId,
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("region_id") Long regionId,
        @JsonProperty("joined_at") OffsetDateTime joinedAt) {

    public static ChatRoomMembershipResponse from(ChatRoomMember membership) {
        return new ChatRoomMembershipResponse(
                membership.getId(),
                membership.getChatRoom().getId(),
                membership.getChatRoom().getRegion().getId(),
                toUtc(membership.getCreatedAt())
        );
    }

    private static OffsetDateTime toUtc(LocalDateTime createdAt) {
        return createdAt.atOffset(ZoneOffset.UTC);
    }
}
