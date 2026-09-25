package com.muse.meomuneum.recommendation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.recommendation.dto.response.SpeechTranscriptionResponse;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

@RestController
@RequestMapping("/api/v1/speech-transcriptions")
public class SpeechTranscriptionController {
    private final SpeechTranscriptionService service;

    public SpeechTranscriptionController(SpeechTranscriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SpeechTranscriptionResponse>> create(@RequestParam("audio") MultipartFile audio) {
        return ResponseEntity.ok(new ApiResponse<>("speech transcription completed", service.transcribe(audio)));
    }

    public record ApiResponse<T>(String message, T data) {
    }
}
