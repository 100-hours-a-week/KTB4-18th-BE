package com.muse.meomuneum.chat.message.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat.message-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ChatMessageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageCleanupJob.class);
    private final ChatMessageStorageService storageService;

    public ChatMessageCleanupJob(ChatMessageStorageService storageService) {
        this.storageService = storageService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void purgeExpiredMessages() {
        try {
            int deleted = storageService.deleteExpiredMessages();
            log.info("event=chat_message_cleanup_success deleted={}", deleted);
        } catch (RuntimeException exception) {
            // The next hourly run retries all still-expired rows; do not log SQL or message bodies.
            log.warn("event=chat_message_cleanup_failed exception={}", exception.getClass().getSimpleName());
        }
    }
}
