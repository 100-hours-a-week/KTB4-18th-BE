package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class MusicSearchIngestWorkerTest {
    @Test
    void claimsOnlyAvailableWorkerCapacityAndDoesNotQueueLeasedJobs() throws Exception {
        var jobs = mock(MusicSearchIngestJobService.class);
        var executor = executor(1);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var job = job(1);
        when(jobs.claim(1, 120)).thenReturn(List.of(job));
        doAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(jobs).complete(job);
        var worker = new MusicSearchIngestWorker(jobs, executor, 1, 20, 120);

        try {
            worker.poll();
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            worker.poll();

            verify(jobs, times(1)).claim(1, 120);
            verify(jobs, never()).claim(20, 120);
        } finally {
            finish.countDown();
            executor.shutdown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void releasesAClaimWhenBoundedDispatchRejectsIt() {
        var jobs = mock(MusicSearchIngestJobService.class);
        var executor = executor(1);
        var job = job(1);
        when(jobs.claim(1, 120)).thenReturn(List.of(job));
        executor.shutdown();
        var worker = new MusicSearchIngestWorker(jobs, executor, 1, 20, 120);

        worker.poll();

        verify(jobs).release(job);
    }

    @Test
    void workerLogsSafeFailureAndCompletionEventsWithoutPayloadOrExceptionMessage() throws Exception {
        var jobs = mock(MusicSearchIngestJobService.class);
        var executor = executor(2);
        var failure = job(1);
        var success = job(2);
        when(jobs.claim(2, 120)).thenReturn(List.of(failure, success));
        doThrow(new TransientDataAccessResourceException("private SQL and password detail"))
                .when(jobs).complete(failure);
        when(jobs.fail(failure, true, "transient_failure")).thenReturn(true);
        var logger = (Logger) LoggerFactory.getLogger(MusicSearchIngestWorker.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var worker = new MusicSearchIngestWorker(jobs, executor, 2, 20, 120);

        try {
            worker.poll();
            executor.shutdown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS)).isTrue();

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains("event=music_search_storage_retry_scheduled", "jobId=1", "provider=ITUNES",
                            "externalId=1", "attempt=1", "category=transient_failure",
                            "exception=TransientDataAccessResourceException"));
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains("event=music_search_storage_done", "jobId=2", "provider=ITUNES",
                            "externalId=2", "attempt=1"));
            assertThat(String.join("\n", messages)).doesNotContain(
                    "private SQL and password detail", "private-provider-payload");
        } finally {
            logger.detachAppender(appender);
            executor.shutdown();
            appender.stop();
        }
    }

    private ThreadPoolTaskExecutor executor(int workers) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }

    private ClaimedJob job(long id) {
        MusicItem item = new MusicItem(null, "ITUNES", Long.toString(id), "private-provider-payload", "artist",
                null, null, null, false);
        return new ClaimedJob(id, item, 1, "lease-token");
    }
}
