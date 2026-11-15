package com.muse.meomuneum.chat.message.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.muse.meomuneum.chat.message.domain.ChatRoomMessage;

public interface ChatRoomMessageRepository extends JpaRepository<ChatRoomMessage, Long> {

    // A locking read avoids an earlier REPEATABLE READ snapshot hiding a completed retry.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChatRoomMessage> findByUser_IdAndClientMessageId(Long userId, String clientMessageId);

    @Query("""
            SELECT message FROM ChatRoomMessage message
            WHERE message.id = :id AND message.createdAt > :cutoff AND message.createdAt <= :now
            """)
    Optional<ChatRoomMessage> findUnexpiredById(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChatRoomMessage message WHERE message.createdAt <= :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
