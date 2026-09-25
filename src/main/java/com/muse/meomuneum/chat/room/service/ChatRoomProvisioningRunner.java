package com.muse.meomuneum.chat.room.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ChatRoomProvisioningRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatRoomProvisioningRunner.class);

    private final ChatRoomProvisioningService provisioningService;

    public ChatRoomProvisioningRunner(ChatRoomProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int createdRoomCount = provisioningService.provisionMissingRooms();
        if (createdRoomCount > 0) {
            log.info("Provisioned {} active SIGUNGU chat rooms", createdRoomCount);
        }
    }
}
