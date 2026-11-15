package com.muse.meomuneum.chat.view;

import java.time.LocalDateTime;
import java.util.List;

/** Union of recorded predicates, never an envelope that bridges absent time or message-id gaps. */
public record ChatAccountReadIntervals(Long userId, Long roomId, LocalDateTime asOf,
        List<ChatViewInterval> intervals) {
    public ChatAccountReadIntervals {
        intervals = List.copyOf(intervals);
    }

    public LocalDateTime cutoff() {
        return asOf.minusHours(24);
    }

    public boolean permits(long messageId, LocalDateTime sentAt) {
        return sentAt.isAfter(cutoff()) && !sentAt.isAfter(asOf())
                && intervals.stream().anyMatch(interval -> interval.permits(messageId, sentAt));
    }
}
