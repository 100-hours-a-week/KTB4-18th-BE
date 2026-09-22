package com.muse.meomuneum.recommendation.dto;

public record SpeechAudio(byte[] content, String mediaType, String originalFilename, double durationSeconds) {}
