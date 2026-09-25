package com.muse.meomuneum.chat.room.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;

@Service
public class ChatRoomProvisioningService {

    private final ChatRoomRepository chatRoomRepository;

    public ChatRoomProvisioningService(ChatRoomRepository chatRoomRepository) {
        this.chatRoomRepository = chatRoomRepository;
    }

    @Transactional
    public int provisionMissingRooms() {
        int createdRoomCount = chatRoomRepository.createMissingActiveSigunguRooms();
        long missingRoomCount = chatRoomRepository.countMissingActiveSigunguRooms();
        if (missingRoomCount > 0) {
            throw new IllegalStateException(
                    "chat room provisioning incomplete: " + missingRoomCount + " active SIGUNGU rooms missing");
        }
        return createdRoomCount;
    }
}
