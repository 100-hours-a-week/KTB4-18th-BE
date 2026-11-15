package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class MusicSearchIngestWorkerTest {
    @Test
    void workerAndAsyncFailureLogsOmitPayloadAndExceptionDetailsWhileCallbacksRun() {
        var jobs = mock(MusicSearchIngestJobService.class);
        var payload = new MusicItem(null, "ITUNES", "7777777", "private-provider-title", "private-provider-artist",
                "private-provider-cover", "private-provider-preview", "private-provider-video", false,
                new StorefrontNames("private-provider-us-title", "private-provider-us-artist",
                        "private-provider-ko-title", "private-provider-ko-artist", false),
                1234567890L);
        var job = new ClaimedJob(17, payload, 1, "private-lease-token", 1, LocalDateTime.now());
        String detail = "private SQL and password detail";
        var asyncLogger = (Logger) LoggerFactory.getLogger(MusicSearchIngestAsyncService.class);
        var workerLogger = (Logger) LoggerFactory.getLogger(MusicSearchIngestWorker.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        asyncLogger.addAppender(appender);
        workerLogger.addAppender(appender);
        try {
            var completions = new AtomicInteger();
            var async = new MusicSearchIngestAsyncService(jobs,
                    new MusicSearchRetryPolicy(5000, 300000, bound -> 0));
            when(jobs.start(job, 120)).thenThrow(new IllegalStateException(detail + " start")).thenReturn(job);
            doThrow(new org.springframework.dao.TransientDataAccessResourceException(detail + " catalog"))
                    .when(jobs).complete(job);
            doThrow(new IllegalArgumentException(detail + " persistence"))
                    .when(jobs).fail(job, true, "transient_failure", 2500);
            async.process(job, 120, completions::incrementAndGet);
            async.process(job, 120, completions::incrementAndGet);
            doNothing().when(jobs).complete(job);
            async.process(job, 120, completions::incrementAndGet);
            assertThat(completions.get()).isEqualTo(3);
            verify(jobs, times(2)).complete(job);
            verify(jobs).fail(job, true, "transient_failure", 2500);

            var rejected = mock(MusicSearchIngestAsyncService.class);
            doThrow(new org.springframework.core.task.TaskRejectedException(detail + " dispatch"))
                    .when(rejected).process(any(), anyInt(), any());
            when(jobs.recover(20, 120)).thenThrow(new IllegalStateException(detail + " recovery"))
                    .thenReturn(List.of());
            when(jobs.claim(2, 120)).thenReturn(List.of(job))
                    .thenThrow(new IllegalStateException(detail + " claim"));
            doThrow(new IllegalStateException(detail + " release")).when(jobs).release(job, 1000);
            var worker = new MusicSearchIngestWorker(jobs, rejected, 1, 1, 20, 120, 1000);
            worker.poll();
            worker.poll();
            verify(jobs, times(2)).claim(2, 120);
            verify(jobs).release(job, 1000);

            var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).containsExactly(
                    "event=music_search_storage_start_failed jobId=17 exception=IllegalStateException",
                    "event=music_search_storage_failure_update_failed jobId=17 exception=IllegalArgumentException",
                    "event=music_search_storage_attempt_failed jobId=17 attempt=1 category=transient_failure "
                            + "exception=TransientDataAccessResourceException",
                    "event=music_search_storage_recovery_failed exception=IllegalStateException",
                    "event=music_search_storage_release_failed jobId=17 exception=IllegalStateException",
                    "event=music_search_storage_claim_failed exception=IllegalStateException");
            assertThat(String.join("\n", messages)).doesNotContain("private-provider-", detail, "private-lease-token",
                    "ITUNES", "7777777", "1234567890");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            asyncLogger.detachAppender(appender);
            workerLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void realAsyncProxyUsesWorkerThreadAndPositiveBoundedQueue() throws Exception {
        var jobs = mock(MusicSearchIngestJobService.class);
        var first = job(1);
        var second = job(2);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        when(jobs.claim(2, 120)).thenReturn(List.of(first, second));
        when(jobs.start(any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            entered.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(jobs).complete(first);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfiguration.class);
            var executor = executor();
            context.registerBean("musicSearchStorageExecutor", ThreadPoolTaskExecutor.class, () -> executor);
            context.registerBean(MusicSearchIngestJobService.class, () -> jobs);
            context.registerBean(MusicSearchRetryPolicy.class, () -> new MusicSearchRetryPolicy(5000, 300000));
            context.registerBean(MusicSearchIngestAsyncService.class);
            context.refresh();
            var async = context.getBean(MusicSearchIngestAsyncService.class);
            var worker = new MusicSearchIngestWorker(jobs, async, 1, 1, 20, 120, 1000);
            try {
                worker.poll();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(thread.get()).startsWith("music-search-test-");
                assertThat(executor.getActiveCount()).isEqualTo(1);
                assertThat(executor.getQueueSize()).isEqualTo(1);
                worker.poll();
                verify(jobs, times(1)).claim(2, 120);
            } finally {
                finish.countDown();
                executor.shutdown();
            }
            verify(jobs).complete(second);
        }
    }

    @Test
    void rejectionReschedulesWithoutStartingAndReturnsReservationExactlyOnce() {
        var jobs = mock(MusicSearchIngestJobService.class);
        var async = mock(MusicSearchIngestAsyncService.class);
        var job = job(1);
        when(jobs.claim(2, 120)).thenReturn(List.of(job));
        doThrow(new org.springframework.core.task.TaskRejectedException("shutdown"))
                .when(async).process(any(), anyInt(), any());
        doThrow(new IllegalStateException("DB unavailable")).when(jobs).release(job, 1000);
        var worker = new MusicSearchIngestWorker(jobs, async, 1, 1, 20, 120, 1000);
        worker.poll();
        worker.poll();
        verify(jobs, times(2)).release(job, 1000);
        verify(jobs, times(2)).claim(2, 120);
    }

    @Test
    void jitterEndpointsCapAndOverflowAreDeterministic() {
        var low = new MusicSearchRetryPolicy(5000, 300000, bound -> 0);
        var high = new MusicSearchRetryPolicy(5000, 300000, bound -> bound - 1);
        assertThat(low.delayMillis(1)).isEqualTo(2500);
        assertThat(high.delayMillis(1)).isEqualTo(5000);
        assertThat(low.delayMillis(2)).isEqualTo(5000);
        assertThat(high.delayMillis(2)).isEqualTo(10000);
        assertThat(low.delayMillis(Integer.MAX_VALUE)).isEqualTo(150000);
        assertThat(high.delayMillis(Integer.MAX_VALUE)).isEqualTo(300000);
    }

    private ThreadPoolTaskExecutor executor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("music-search-test-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    private ClaimedJob job(long id) {
        return new ClaimedJob(id, new MusicItem(null, "ITUNES", Long.toString(id), "song", "artist",
                null, null, null, false), 1, "token-" + id, 1, LocalDateTime.now());
    }

    @EnableAsync
    static class AsyncConfiguration {
    }
}
