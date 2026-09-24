package com.muse.meomuneum.chat.room.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.muse.meomuneum.chat.room.domain.ChatRoom;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByRegion_Id(Long regionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO chat_rooms (region_id, capacity, status)
            SELECT region.id, 25, 'ACTIVE'
            FROM regions AS region
            LEFT JOIN chat_rooms AS room ON room.region_id = region.id
            WHERE region.level = 'SIGUNGU'
                AND region.is_active = TRUE
                AND room.id IS NULL
            """, nativeQuery = true)
    int createMissingActiveSigunguRooms();

    @Query(value = """
            SELECT COUNT(*)
            FROM regions AS region
            LEFT JOIN chat_rooms AS room ON room.region_id = region.id
            WHERE region.level = 'SIGUNGU'
                AND region.is_active = TRUE
                AND room.id IS NULL
            """, nativeQuery = true)
    long countMissingActiveSigunguRooms();
}
