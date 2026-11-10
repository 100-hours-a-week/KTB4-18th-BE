package com.muse.meomuneum.musicrecord.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestJobRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestJobRepository.ClaimCandidate;

@Service
public class MusicSearchIngestJobService {
    private final MusicSearchIngestJobRepository jobs;
    private final MusicRecordRepository music;

    public MusicSearchIngestJobService(MusicSearchIngestJobRepository jobs, MusicRecordRepository music) {
        this.jobs = jobs;
        this.music = music;
    }

    @Transactional
    public void admit(List<MusicItem> snapshot) {
        jobs.admit(snapshot);
    }

    @Transactional
    public List<ClaimedJob> claim(int limit, int leaseSeconds) {
        jobs.failExhaustedLeases();
        List<ClaimCandidate> candidates = jobs.findClaimable(limit);
        List<ClaimedJob> claimed = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            String token = UUID.randomUUID().toString();
            if (jobs.claim(candidate.id(), token, leaseSeconds) == 1) {
                claimed.add(new ClaimedJob(candidate.id(), candidate.music(), candidate.attemptCount() + 1, token));
            }
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public void complete(ClaimedJob job) {
        MusicItem payload = jobs.findClaimedPayload(job.id(), job.leaseToken())
                .orElseThrow(() -> new IllegalStateException("music search job lease is no longer active"));
        MusicSearchStorageService.validate(payload);
        music.upsertMusic(payload);
        if (jobs.markDone(job.id(), job.leaseToken()) != 1) {
            throw new IllegalStateException("music search job lease is no longer active");
        }
    }

    @Transactional
    public boolean fail(ClaimedJob job, boolean retryable, String errorCode) {
        return jobs.markFailure(job.id(), job.leaseToken(), job.attemptCount(), retryable, errorCode) == 1;
    }

    @Transactional
    public boolean release(ClaimedJob job) {
        return jobs.release(job.id(), job.leaseToken(), job.attemptCount()) == 1;
    }

    @Transactional
    public RequeueResult requeue(long jobId) {
        if (jobs.requeue(jobId) == 1) {
            return new RequeueResult(jobId, "QUEUED");
        }
        if (!jobs.exists(jobId)) {
            throw new MusicRecordException("music_search_job_not_found", HttpStatus.NOT_FOUND,
                    "music search job not found");
        }
        throw new MusicRecordException("music_search_job_not_failed", HttpStatus.CONFLICT,
                "music search job is not failed");
    }

    public record ClaimedJob(long id, MusicItem music, int attemptCount, String leaseToken) {
    }

    public record RequeueResult(long job_id, String status) {
    }
}
