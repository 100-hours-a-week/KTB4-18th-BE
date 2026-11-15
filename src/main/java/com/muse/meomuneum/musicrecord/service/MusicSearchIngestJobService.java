package com.muse.meomuneum.musicrecord.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestDlqRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestJobRepository;

@Service
public class MusicSearchIngestJobService {
    private final MusicSearchIngestJobRepository jobs;
    private final MusicRecordRepository music;
    private final MusicSearchIngestDlqRepository dlq;
    private final int maxAttempts;

    public MusicSearchIngestJobService(MusicSearchIngestJobRepository jobs, MusicRecordRepository music,
            MusicSearchIngestDlqRepository dlq,
            @Value("${music.search-storage.max-attempts:3}") int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("music search storage max attempts must be positive");
        }
        this.jobs = jobs;
        this.music = music;
        this.dlq = dlq;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void admit(List<MusicItem> snapshot) {
        jobs.admit(snapshot);
    }

    @Transactional
    public List<ClaimedJob> claim(int limit, int leaseSeconds) {
        List<ClaimedJob> claimed = new ArrayList<>();
        for (var state : jobs.findDue(limit, maxAttempts)) {
            var job = withToken(state.job(), UUID.randomUUID().toString());
            jobs.claim(job.id(), job.leaseToken(), leaseSeconds);
            claimed.add(job);
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public List<ClaimedJob> recover(int limit, int leaseSeconds) {
        List<ClaimedJob> started = new ArrayList<>();
        for (var state : jobs.findRecoverable(limit, maxAttempts)) {
            if (state.attemptStartedAt() == null && state.job().attemptCount() < maxAttempts) {
                jobs.retry(state.job().id(), 0, "worker_lease_unstarted");
            } else {
                var job = withToken(state.job(), UUID.randomUUID().toString());
                jobs.claim(job.id(), job.leaseToken(), leaseSeconds);
                started.add(job);
            }
        }
        return List.copyOf(started);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimedJob start(ClaimedJob job, int leaseSeconds) {
        var state = jobs.lock(job.id()).orElse(null);
        if (state == null || !jobs.active(job, true) || state.attemptStartedAt() != null
                || state.job().attemptCount() >= maxAttempts) {
            return null;
        }
        jobs.start(job, leaseSeconds);
        return new ClaimedJob(job.id(), state.job().music(), state.job().attemptCount() + 1, job.leaseToken(),
                job.payloadVersion(), state.job().observedAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(ClaimedJob job) {
        var state = jobs.lock(job.id()).orElse(null);
        if (state == null || !jobs.active(job, true) || state.attemptStartedAt() == null) {
            return;
        }
        if (!state.job().music().equals(job.music()) || state.job().attemptCount() != job.attemptCount()) {
            throw new IllegalStateException("music search snapshot identity mismatch");
        }
        MusicSearchStorageService.validate(job.music());
        music.upsertSearchMusic(job.music(), state.job().observedAt(), job.payloadVersion());
        jobs.done(job.id());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(ClaimedJob job, boolean retryable, String errorCode, long delayMillis) {
        var state = jobs.lock(job.id()).orElse(null);
        if (state == null || !jobs.active(job, false)) {
            return false;
        }
        if (retryable && state.job().attemptCount() < maxAttempts) {
            jobs.retry(job.id(), delayMillis, errorCode);
        } else {
            String terminalCode = retryable && state.job().attemptCount() >= maxAttempts
                    ? "attempts_exhausted"
                    : errorCode;
            dlq.insert(state.job(), terminalCode);
            jobs.dead(job.id(), terminalCode);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(ClaimedJob job, long delayMillis) {
        var state = jobs.lock(job.id()).orElse(null);
        if (state == null || !jobs.active(job, false) || state.attemptStartedAt() != null) {
            return false;
        }
        jobs.retry(job.id(), delayMillis, "worker_dispatch_failed");
        return true;
    }

    @Transactional
    public RequeueResult requeue(long jobId) {
        var state = jobs.lock(jobId).orElseThrow(() -> new MusicRecordException("music_search_job_not_found",
                HttpStatus.NOT_FOUND, "music search job not found"));
        if (!"DEAD".equals(state.status()) || !dlq.markReplayed(jobId)) {
            throw new MusicRecordException("music_search_job_not_dead", HttpStatus.CONFLICT,
                    "music search job is not dead");
        }
        jobs.replay(jobId);
        return new RequeueResult(jobId, "QUEUED");
    }

    private ClaimedJob withToken(ClaimedJob job, String token) {
        return new ClaimedJob(job.id(), job.music(), job.attemptCount(), token, job.payloadVersion(), job.observedAt());
    }

    public record ClaimedJob(long id, MusicItem music, int attemptCount, String leaseToken,
            long payloadVersion, LocalDateTime observedAt) {
    }

    public record RequeueResult(long job_id, String status) {
    }
}
