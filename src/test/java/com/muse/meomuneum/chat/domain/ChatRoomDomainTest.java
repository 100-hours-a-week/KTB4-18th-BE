package com.muse.meomuneum.chat.domain;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.domain.ChatRoomStatus;
import com.muse.meomuneum.user.domain.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatRoomDomainTest {

    @Test
    void createsActiveChatRoomForSigunguWithDefaultCapacity() {
        Region sido = Region.create("41", "경기도", RegionLevel.SIDO, null);
        Region sigungu = Region.create("41135", "성남시 분당구", RegionLevel.SIGUNGU, sido);

        ChatRoom chatRoom = ChatRoom.create(sigungu);

        assertEquals(ChatRoom.DEFAULT_CAPACITY, chatRoom.getCapacity());
        assertEquals(ChatRoomStatus.ACTIVE, chatRoom.getStatus());
        assertEquals(sigungu, chatRoom.getRegion());
    }

    @Test
    void rejectsChatRoomForSido() {
        Region sido = Region.create("41", "경기도", RegionLevel.SIDO, null);

        assertThrows(IllegalArgumentException.class, () -> ChatRoom.create(sido));
    }

    @Test
    void marksMembershipInactiveOnlyOnce() {
        Region sido = Region.create("41", "경기도", RegionLevel.SIDO, null);
        Region sigungu = Region.create("41135", "성남시 분당구", RegionLevel.SIGUNGU, sido);
        ChatRoomMember member = ChatRoomMember.join(mock(User.class), ChatRoom.create(sigungu));
        LocalDateTime firstLeaveTime = LocalDateTime.of(2026, 9, 23, 14, 30);

        member.leave(firstLeaveTime);
        member.leave(firstLeaveTime.plusMinutes(1));

        assertFalse(member.isActive());
        assertEquals(firstLeaveTime, member.getDeletedAt());
    }

    @Test
    void validatesRegionHierarchy() {
        Region sido = Region.create("41", "경기도", RegionLevel.SIDO, null);

        assertTrue(sido.isActive());
        assertThrows(IllegalArgumentException.class,
                () -> Region.create("41135", "성남시 분당구", RegionLevel.SIGUNGU, null));
        assertThrows(IllegalArgumentException.class,
                () -> Region.create("11", "서울특별시", RegionLevel.SIDO, sido));
    }
}
