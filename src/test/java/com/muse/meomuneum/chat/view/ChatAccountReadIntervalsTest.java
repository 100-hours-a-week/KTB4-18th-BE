package com.muse.meomuneum.chat.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChatAccountReadIntervalsTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 18, 0, 0);

    @Test
    void unionPreservesJointTimeAndIdBoundsInsteadOfFillingTheirEnvelope() {
        var ranges = new ChatAccountReadIntervals(1L, 2L, START.plusSeconds(100), List.of(
                new ChatViewInterval(1, 10, START, START.plusSeconds(10), 20L),
                new ChatViewInterval(2, 30, START.plusSeconds(5), START.plusSeconds(20), 40L)));
        assertTrue(ranges.permits(15, START.plusSeconds(1)));
        assertTrue(ranges.permits(35, START.plusSeconds(6)));
        assertFalse(ranges.permits(25, START.plusSeconds(6)));
        assertFalse(ranges.permits(35, START.plusSeconds(1)));
        assertFalse(ranges.permits(10, START));
    }

    @Test
    void sameMicrosecondUsesExclusiveStartAndInclusiveRecordedEnd() {
        var ranges = new ChatAccountReadIntervals(1L, 2L, START, List.of(
                new ChatViewInterval(1, 10, START, START, 12L)));
        assertFalse(ranges.permits(10, START));
        assertTrue(ranges.permits(11, START));
        assertTrue(ranges.permits(12, START));
        assertFalse(ranges.permits(13, START));
    }

    @Test
    void retentionGraceAndSnapshotBoundariesRemainExclusiveWhereRequired() {
        var ranges = new ChatAccountReadIntervals(1L, 2L, START.plusHours(24), List.of(
                new ChatViewInterval(1, 0, START, START.plusHours(25), null)));
        assertFalse(ranges.permits(1, START));
        assertTrue(ranges.permits(2, START.plusNanos(1000)));
        assertTrue(ranges.permits(3, START.plusHours(24)));
        assertFalse(ranges.permits(4, START.plusHours(24).plusNanos(1000)));
        var grace = new ChatAccountReadIntervals(1L, 2L, START.plusSeconds(60), List.of(
                new ChatViewInterval(1, 0, START, START.plusSeconds(30), null)));
        assertTrue(grace.permits(1, START.plusSeconds(29)));
        assertFalse(grace.permits(2, START.plusSeconds(30)));
    }

    @Test
    void resultIsImmutableAndEmptyRecordedHistoryDoesNotGrantAccess() {
        var source = new ArrayList<ChatViewInterval>();
        var result = new ChatAccountReadIntervals(1L, 2L, START, source);
        source.add(new ChatViewInterval(1, 0, START, START, 10L));
        assertFalse(result.permits(1, START));
    }
}
