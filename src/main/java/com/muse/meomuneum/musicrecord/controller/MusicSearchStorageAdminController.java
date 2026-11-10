package com.muse.meomuneum.musicrecord.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.RequeueResult;

@RestController
@RequestMapping("/api/v1/admin/music-search-storage")
public class MusicSearchStorageAdminController {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchStorageAdminController.class);
    private final MusicSearchIngestJobService jobs;

    public MusicSearchStorageAdminController(MusicSearchIngestJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping("/{jobId}/requeue")
    public ApiResponse<RequeueResult> requeue(@PathVariable long jobId) {
        try {
            return ApiResponse.of("music search job requeued", jobs.requeue(jobId));
        } catch (MusicRecordException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("event=music_search_job_requeue_unavailable jobId={} exception={}", jobId,
                    exception.getClass().getSimpleName());
            throw new MusicRecordException("music_search_job_requeue_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "music search storage unavailable");
        }
    }
}
