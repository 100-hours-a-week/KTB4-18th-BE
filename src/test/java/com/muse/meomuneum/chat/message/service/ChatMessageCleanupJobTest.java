package com.muse.meomuneum.chat.message.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

class ChatMessageCleanupJobTest {

    @Test
    void retriesRemainingExpiredMessagesOnTheNextInvocationAfterFailure() {
        ChatMessageStorageService storage = mock(ChatMessageStorageService.class);
        when(storage.deleteExpiredMessages()).thenThrow(new DataAccessResourceFailureException("test-only"))
                .thenReturn(2).thenReturn(0);
        ChatMessageCleanupJob job = new ChatMessageCleanupJob(storage);

        assertDoesNotThrow(job::purgeExpiredMessages);
        assertDoesNotThrow(job::purgeExpiredMessages);
        assertDoesNotThrow(job::purgeExpiredMessages);
        verify(storage, times(3)).deleteExpiredMessages();
    }

    @Test
    void runsAtEveryUtcHour() throws NoSuchMethodException {
        Scheduled scheduled = ChatMessageCleanupJob.class.getMethod("purgeExpiredMessages")
                .getAnnotation(Scheduled.class);

        assertNotNull(scheduled);
        assertEquals("0 0 * * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
