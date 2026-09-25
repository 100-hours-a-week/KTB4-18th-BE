package com.muse.meomuneum.chat.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.muse.meomuneum.chat.member.domain.ChatRoomMember;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    @EntityGraph(attributePaths = {"chatRoom", "chatRoom.region"})
    Optional<ChatRoomMember> findByUser_IdAndDeletedAtIsNull(Long userId);

    long countByChatRoom_IdAndDeletedAtIsNull(Long roomId);
}
