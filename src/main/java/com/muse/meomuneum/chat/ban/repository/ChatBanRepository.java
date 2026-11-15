package com.muse.meomuneum.chat.ban.repository;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.muse.meomuneum.chat.ban.domain.ChatBan;

public interface ChatBanRepository extends JpaRepository<ChatBan, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ban FROM ChatBan ban WHERE ban.user.id = :userId AND ban.deletedAt IS NULL
            AND ban.createdAt <= :now AND ban.expiresAt > :now ORDER BY ban.expiresAt DESC, ban.id DESC
            """)
    List<ChatBan> findActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now,
            Pageable pageable);

    boolean existsByUser_IdAndDeletedAtIsNullAndCreatedAtLessThanEqualAndExpiresAtGreaterThan(Long userId,
            LocalDateTime startedBy, LocalDateTime now);
}
