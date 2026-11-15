package com.muse.meomuneum.musicrecord.service;

import java.util.List;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

@Component
public class MusicSearchIngestWorker {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchIngestWorker.class);
    private final MusicSearchIngestJobService jobs;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore capacity;
    private final int batchSize;
    private final int leaseSeconds;

    public MusicSearchIngestWorker(MusicSearchIngestJobService jobs,
            @Qualifier("musicSearchStorageExecutor") ThreadPoolTaskExecutor executor,
            @Value("${music.search-storage.workers:2}") int workers,
            @Value("${music.search-storage.batch-size:20}") int batchSize,
            @Value("${music.search-storage.lease-seconds:120}") int leaseSeconds) {
        if (workers <= 0 || batchSize <= 0 || leaseSeconds <= 0) {
            throw new IllegalArgumentException("music search storage worker settings must be positive");
        }
        this.jobs = jobs;
        this.executor = executor;
        this.capacity = new Semaphore(workers);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${music.search-storage.poll-delay-ms:1000}")
    public void poll() {
        int reserved = reserveCapacity();
        if (reserved == 0) {
            return;
        }
        List<ClaimedJob> claimed;
        try {
            claimed = jobs.claim(reserved, leaseSeconds);
        } catch (RuntimeException exception) {
            capacity.release(reserved);
            log.warn("event=music_search_storage_claim_failed exception={}",
                    exception.getClass().getSimpleName());
            return;
        }
        capacity.release(reserved - claimed.size());
        for (ClaimedJob job : claimed) {
            try {
                executor.execute(() -> process(job));
            } catch (RuntimeException exception) {
                release(job, exception);
            }
        }
    }

    private int reserveCapacity() {
        int reserved = 0;
        while (reserved < batchSize && capacity.tryAcquire()) {
            reserved++;
        }
        return reserved;
    }

    private void process(ClaimedJob job) {
        try {
            jobs.complete(job);
            log.info("event=music_search_storage_done jobId={} provider={} externalId={} attempt={}",
                    job.id(), job.music().provider(), job.music().external_music_id(), job.attemptCount());
        } catch (RuntimeException exception) {
            fail(job, exception);
        } finally {
            capacity.release();
        }
    }

    private void fail(ClaimedJob job, RuntimeException failure) {
        boolean retryable = MusicSearchStorageService.isRetryable(failure);
        String errorCode = errorCode(failure, retryable);
        try {
            boolean updated = jobs.fail(job, retryable, errorCode);
            if (updated) {
                String state = retryable && job.attemptCount() < 3 ? "retry_scheduled" : "failed";
                log.warn("event=music_search_storage_{} jobId={} provider={} externalId={} attempt={} "
                        + "category={} exception={}", state, job.id(), job.music().provider(),
                        job.music().external_music_id(), job.attemptCount(), errorCode,
                        failure.getClass().getSimpleName());
            } else {
                log.info("event=music_search_storage_stale_failure jobId={} provider={} externalId={} attempt={}",
                        job.id(), job.music().provider(), job.music().external_music_id(), job.attemptCount());
            }
        } catch (RuntimeException updateFailure) {
            log.error("event=music_search_storage_failure_update_failed jobId={} provider={} externalId={} "
                    + "attempt={} exception={}", job.id(), job.music().provider(),
                    job.music().external_music_id(), job.attemptCount(), updateFailure.getClass().getSimpleName());
        }
    }

    private String errorCode(RuntimeException failure, boolean retryable) {
        if (failure instanceof MusicMetadataException) {
            return "metadata_invalid";
        }
        return retryable ? "transient_failure" : "permanent_failure";
    }

    private void release(ClaimedJob job, RuntimeException failure) {
        try {
            boolean updated = jobs.release(job);
            log.warn("event=music_search_storage_dispatch_failed jobId={} provider={} externalId={} attempt={} "
                    + "released={} exception={}", job.id(), job.music().provider(),
                    job.music().external_music_id(), job.attemptCount(), updated,
                    failure.getClass().getSimpleName());
        } catch (RuntimeException updateFailure) {
            log.error("event=music_search_storage_release_failed jobId={} provider={} externalId={} "
                    + "attempt={} exception={}", job.id(), job.music().provider(),
                    job.music().external_music_id(), job.attemptCount(), updateFailure.getClass().getSimpleName());
        } finally {
            capacity.release();
        }
    }
}
