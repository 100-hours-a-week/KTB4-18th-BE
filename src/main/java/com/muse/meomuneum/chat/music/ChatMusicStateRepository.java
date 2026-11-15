package com.muse.meomuneum.chat.music;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Storage boundary only; callers must authorize participation before changing music state. */
@Repository
public class ChatMusicStateRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;

    public ChatMusicStateRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        writes = new TransactionTemplate(manager);
        writes.setTimeout(3);
    }

    public void initializeRoom(long roomId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("music state initialization requires a writable transaction");
        }
        if (roomId <= 0) {
            throw new IllegalArgumentException("room id required");
        }
        writes.executeWithoutResult(tx -> jdbc.update("""
                INSERT INTO playback_states (chat_room_id) VALUES (?)
                ON DUPLICATE KEY UPDATE chat_room_id=playback_states.chat_room_id
                """, roomId));
    }

    public LockedState lockRoom(long roomId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("music room lock requires an existing transaction");
        }
        initializeRoom(roomId);
        return jdbc.queryForObject("""
                SELECT current_queue_item_id,state,position_seconds,state_changed_at,version,next_sequence_no
                FROM playback_states WHERE chat_room_id=? FOR UPDATE
                """, (row, index) -> new LockedState(row.getObject("current_queue_item_id", Long.class),
                row.getString("state"), row.getBigDecimal("position_seconds"),
                row.getObject("state_changed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getLong("version"),
                row.getLong("next_sequence_no")), roomId);
    }

    public record LockedState(Long queueItemId, String state, BigDecimal positionSeconds, Instant stateChangedAt,
            long version,
            long nextSequenceNo) {
    }
}
