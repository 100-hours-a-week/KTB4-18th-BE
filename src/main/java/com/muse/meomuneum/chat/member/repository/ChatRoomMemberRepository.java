package com.muse.meomuneum.chat.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.muse.meomuneum.chat.member.domain.ChatRoomMember;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    Optional<ChatRoomMember> findByUser_IdAndDeletedAtIsNull(Long userId);

    long countByChatRoom_IdAndDeletedAtIsNull(Long roomId);
}
