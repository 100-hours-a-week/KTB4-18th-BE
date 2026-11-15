package com.muse.meomuneum.musicrecord.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

@Service
public class MusicSearchIngestAsyncService {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchIngestAsyncService.class);
    private final MusicSearchIngestJobService jobs;
    private final MusicSearchRetryPolicy retryPolicy;

    public MusicSearchIngestAsyncService(MusicSearchIngestJobService jobs, MusicSearchRetryPolicy retryPolicy) {
        this.jobs = jobs;
        this.retryPolicy = retryPolicy;
    }

    @Async("musicSearchStorageExecutor")
    public void process(ClaimedJob claimed, int leaseSeconds, Runnable completion) {
        ClaimedJob started = null;
        try {
            started = jobs.start(claimed, leaseSeconds);
            if (started != null) {
                jobs.complete(started);
            }
        } catch (RuntimeException failure) {
            if (started != null) {
                fail(started, failure);
            } else {
                log.warn("event=music_search_storage_start_failed jobId={} exception={}", claimed.id(),
                        failure.getClass().getSimpleName());
            }
        } finally {
            completion.run();
        }
    }

    public void recover(ClaimedJob job) {
        fail(job, new org.springframework.dao.TransientDataAccessResourceException("worker lease expired"));
    }

    private void fail(ClaimedJob job, RuntimeException failure) {
        boolean retryable = MusicSearchStorageService.isRetryable(failure);
        String category = failure instanceof MusicMetadataException
                ? "metadata_invalid"
                : retryable ? "transient_failure" : "permanent_failure";
        long delayMillis = retryPolicy.delayMillis(job.attemptCount());
        try {
            jobs.fail(job, retryable, category, delayMillis);
        } catch (RuntimeException persistenceFailure) {
            log.error("event=music_search_storage_failure_update_failed jobId={} exception={}", job.id(),
                    persistenceFailure.getClass().getSimpleName());
        }
        log.warn("event=music_search_storage_attempt_failed jobId={} attempt={} category={} exception={}",
                job.id(), job.attemptCount(), category, failure.getClass().getSimpleName());
    }
}
