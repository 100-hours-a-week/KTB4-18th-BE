package com.muse.meomuneum.chat.message.query;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;

public record ChatMessagePage(List<ChatMessageResponse> items, @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_next") boolean hasNext, @JsonProperty("snapshot_message_id") long snapshotMessageId,
        List<Gap> gaps) {
    public record Gap(String reason, @JsonProperty("before_message_id") long beforeMessageId,
            @JsonProperty("resumed_at") OffsetDateTime resumedAt) {
    }
}
