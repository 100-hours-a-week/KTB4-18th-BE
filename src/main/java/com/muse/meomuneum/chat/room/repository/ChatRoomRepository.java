package com.muse.meomuneum.chat.room.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.muse.meomuneum.chat.room.domain.ChatRoom;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByRegion_Id(Long regionId);
}
