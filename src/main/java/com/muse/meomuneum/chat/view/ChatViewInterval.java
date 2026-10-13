package com.muse.meomuneum.chat.view;

import java.time.LocalDateTime;

/** Server-recorded boundaries; start is exclusive by message id, timed grace end is exclusive. */
public record ChatViewInterval(long id, long startAfterMessageId, LocalDateTime startedAt,
        LocalDateTime endsAt, Long endMessageId) {
    public boolean permits(long messageId, LocalDateTime sentAt) {
        return messageId > startAfterMessageId && !sentAt.isBefore(startedAt)
                && (endMessageId != null ? messageId <= endMessageId : sentAt.isBefore(endsAt));
    }
}
