package com.muse.meomuneum.chat.room.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRoomProvisioningServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private ChatRoomProvisioningService provisioningService;

    @Test
    void returnsTheNumberOfNewlyProvisionedRooms() {
        when(chatRoomRepository.createMissingActiveSigunguRooms()).thenReturn(269);

        int createdRoomCount = provisioningService.provisionMissingRooms();

        assertEquals(269, createdRoomCount);
        verify(chatRoomRepository).createMissingActiveSigunguRooms();
    }

    @Test
    void repeatedProvisioningCreatesNoDuplicateRooms() {
        when(chatRoomRepository.createMissingActiveSigunguRooms()).thenReturn(269, 0);

        assertEquals(269, provisioningService.provisionMissingRooms());
        assertEquals(0, provisioningService.provisionMissingRooms());
    }
}
