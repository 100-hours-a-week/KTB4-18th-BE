package com.muse.meomuneum.chat.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

class ChatControlPresenceWatermarkTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatParticipationService participation = mock(ChatParticipationService.class);
    private final ChatRedisChannels channels = mock(ChatRedisChannels.class);
    private final ChatControlEventConsumer consumer = new ChatControlEventConsumer(jdbc, participation,
            mock(ChatViewLifecycle.class), mock(ChatSocketSessions.class), "test");

    private void ready() {
        when(participation.epoch()).thenReturn(1L);
        when(channels.roomChannel(anyLong())).thenAnswer(call -> "room:" + call.getArgument(0));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    }

    @Test
    void latestSnapshotCoalescesOnlyCoveredHintsAndNewChangesStillPublish() {
        ready();
        when(participation.publishPresence(1)).thenReturn(100L, 120L);
        receive(event(1, 10));
        receive(event(1, 20));
        receive(event(1, 100));
        verify(participation, times(1)).publishPresence(1);
        receive(event(1, 101));
        verify(participation, times(2)).publishPresence(1);
        assertEquals(4, consumer.appliedEvents());
        verify(jdbc, times(4)).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
        verify(participation, times(4)).validSockets();
    }

    @Test
    void forgedUncommittedHintCannotAdvanceWatermarkAndOtherRoomIsIndependent() {
        ready();
        when(participation.publishPresence(1)).thenReturn(100L);
        when(participation.publishPresence(2)).thenReturn(200L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0, 1, 1);
        receive(event(1, 1000));
        verify(participation, never()).publishPresence(anyLong());
        receive(event(1, 10));
        receive(event(2, 10));
        verify(participation).publishPresence(1);
        verify(participation).publishPresence(2);
        assertEquals(1, consumer.rejectedEvents());
    }

    @Test
    void failedSnapshotDoesNotMarkHintsCoveredAndRegressingSnapshotFailsClosed() {
        ready();
        when(participation.publishPresence(1)).thenThrow(new IllegalStateException()).thenReturn(100L, 90L);
        var first = event(1, 10);
        assertThrows(IllegalStateException.class, () -> receive(first));
        receive(first);
        assertEquals(1, consumer.appliedEvents());
        assertThrows(ChatParticipationUnavailableException.class, () -> receive(event(1, 101)));
        verify(participation, times(3)).publishPresence(1);
    }

    private ChatControlEvent event(long room, long sequence) {
        return new ChatControlEvent(UUID.randomUUID().toString(), "CHAT_PRESENCE_CHANGED", "chat:" + UUID.randomUUID(),
                Instant.now(), 1, Map.of("room_id", room, "room_seq", sequence));
    }

    private void receive(ChatControlEvent event) {
        consumer.receive(event.channel(channels), channels, event.encode());
    }
}
