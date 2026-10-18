package com.muse.meomuneum.chat.room.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;

public record ChatRoomMembershipResponse(@JsonProperty("membership_id") Long membershipId,
        @JsonProperty("room_id") Long roomId, @JsonProperty("region_id") Long regionId,
        @JsonProperty("joined_at") OffsetDateTime joinedAt,
        @JsonProperty("view_session_id") String viewSessionId) {

    public static ChatRoomMembershipResponse from(ChatRoomMember membership) {
        return new ChatRoomMembershipResponse(membership.getId(), membership.getChatRoom().getId(),
                membership.getChatRoom().getRegion().getId(), toUtc(membership.getCreatedAt()));
    }

    public ChatRoomMembershipResponse(Long membershipId, Long roomId, Long regionId, OffsetDateTime joinedAt) {
        this(membershipId, roomId, regionId, joinedAt, null);
    }

    public ChatRoomMembershipResponse withView(String viewId) {
        return new ChatRoomMembershipResponse(membershipId, roomId, regionId, joinedAt, viewId);
    }

    private static OffsetDateTime toUtc(LocalDateTime createdAt) {
        return createdAt.atOffset(ZoneOffset.UTC);
    }
}
