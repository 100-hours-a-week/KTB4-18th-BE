package com.muse.meomuneum.chat.room.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.muse.meomuneum.chat.room.domain.ChatRoom;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @EntityGraph(attributePaths = "region")
    Optional<ChatRoom> findByRegion_Id(Long regionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT room
            FROM ChatRoom room
            JOIN FETCH room.region
            WHERE room.id IN :roomIds
            ORDER BY room.id
            """)
    List<ChatRoom> findAllByIdForUpdate(@Param("roomIds") List<Long> roomIds);

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
