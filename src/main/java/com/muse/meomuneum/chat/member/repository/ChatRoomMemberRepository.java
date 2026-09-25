package com.muse.meomuneum.chat.member.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.muse.meomuneum.chat.member.domain.ChatRoomMember;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    @EntityGraph(attributePaths = {"chatRoom", "chatRoom.region"})
    Optional<ChatRoomMember> findByUser_IdAndDeletedAtIsNull(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT member
            FROM ChatRoomMember member
            WHERE member.chatRoom.id = :roomId
                AND member.deletedAt IS NULL
            """)
    List<ChatRoomMember> findAllActiveByChatRoomIdForUpdate(@Param("roomId") Long roomId);

    long countByChatRoom_IdAndDeletedAtIsNull(Long roomId);
}
