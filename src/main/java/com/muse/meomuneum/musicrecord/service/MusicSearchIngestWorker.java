package com.muse.meomuneum.musicrecord.service;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

@Component
public class MusicSearchIngestWorker {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchIngestWorker.class);
    private final MusicSearchIngestJobService jobs;
    private final MusicSearchIngestAsyncService async;
    private final Semaphore capacity;
    private final int batchSize;
    private final int leaseSeconds;
    private final long pollDelayMillis;

    public MusicSearchIngestWorker(MusicSearchIngestJobService jobs, MusicSearchIngestAsyncService async,
            @Value("${music.search-storage.workers:2}") int workers,
            @Value("${music.search-storage.queue-capacity:20}") int queueCapacity,
            @Value("${music.search-storage.batch-size:20}") int batchSize,
            @Value("${music.search-storage.lease-seconds:120}") int leaseSeconds,
            @Value("${music.search-storage.poll-delay-ms:1000}") long pollDelayMillis) {
        if (workers <= 0 || queueCapacity <= 0 || batchSize <= 0 || leaseSeconds <= 0 || pollDelayMillis <= 0
                || (long) workers + queueCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("music search storage worker settings must be positive");
        }
        this.jobs = jobs;
        this.async = async;
        this.capacity = new Semaphore(workers + queueCapacity);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.pollDelayMillis = pollDelayMillis;
    }

    @Scheduled(fixedDelayString = "${music.search-storage.poll-delay-ms:1000}")
    public void poll() {
        try {
            jobs.recover(batchSize, leaseSeconds).forEach(async::recover);
        } catch (RuntimeException failure) {
            log.warn("event=music_search_storage_recovery_failed exception={}", failure.getClass().getSimpleName());
        }
        int reserved = 0;
        while (reserved < batchSize && capacity.tryAcquire()) {
            reserved++;
        }
        if (reserved == 0) {
            return;
        }
        List<ClaimedJob> claimed;
        try {
            claimed = jobs.claim(reserved, leaseSeconds);
        } catch (RuntimeException failure) {
            capacity.release(reserved);
            log.warn("event=music_search_storage_claim_failed exception={}", failure.getClass().getSimpleName());
            return;
        }
        capacity.release(reserved - claimed.size());
        for (ClaimedJob job : claimed) {
            AtomicBoolean released = new AtomicBoolean();
            Runnable completion = () -> {
                if (released.compareAndSet(false, true)) {
                    capacity.release();
                }
            };
            try {
                async.process(job, leaseSeconds, completion);
            } catch (RuntimeException failure) {
                try {
                    jobs.release(job, pollDelayMillis);
                } catch (RuntimeException persistenceFailure) {
                    log.error("event=music_search_storage_release_failed jobId={} exception={}", job.id(),
                            persistenceFailure.getClass().getSimpleName());
                } finally {
                    completion.run();
                }
            }
        }
    }
}
