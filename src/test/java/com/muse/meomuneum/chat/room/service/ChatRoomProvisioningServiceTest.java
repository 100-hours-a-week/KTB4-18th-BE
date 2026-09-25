package com.muse.meomuneum.chat.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;

@ExtendWith(MockitoExtension.class)
class ChatRoomProvisioningServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private ChatRoomProvisioningService provisioningService;

    @Test
    void returnsTheNumberOfNewlyProvisionedRooms() {
        when(chatRoomRepository.createMissingActiveSigunguRooms()).thenReturn(269);
        when(chatRoomRepository.countMissingActiveSigunguRooms()).thenReturn(0L);

        int createdRoomCount = provisioningService.provisionMissingRooms();

        assertEquals(269, createdRoomCount);
        verify(chatRoomRepository).createMissingActiveSigunguRooms();
        verify(chatRoomRepository).countMissingActiveSigunguRooms();
    }

    @Test
    void repeatedProvisioningCreatesNoDuplicateRooms() {
        when(chatRoomRepository.createMissingActiveSigunguRooms()).thenReturn(269, 0);
        when(chatRoomRepository.countMissingActiveSigunguRooms()).thenReturn(0L);

        assertEquals(269, provisioningService.provisionMissingRooms());
        assertEquals(0, provisioningService.provisionMissingRooms());
    }

    @Test
    void failsWhenAnActiveSigunguStillHasNoRoom() {
        when(chatRoomRepository.createMissingActiveSigunguRooms()).thenReturn(268);
        when(chatRoomRepository.countMissingActiveSigunguRooms()).thenReturn(1L);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                provisioningService::provisionMissingRooms);

        assertEquals("chat room provisioning incomplete: 1 active SIGUNGU rooms missing", exception.getMessage());
    }
}
