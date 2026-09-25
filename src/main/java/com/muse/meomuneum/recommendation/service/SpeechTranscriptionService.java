package com.muse.meomuneum.recommendation.service;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;
import com.muse.meomuneum.recommendation.dto.response.SpeechTranscriptionResponse;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.media.AudioMetadataInspector;
import com.muse.meomuneum.recommendation.provider.SpeechToTextProvider;

@Service
public class SpeechTranscriptionService {
    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    static final double MAX_DURATION_SECONDS = 60d;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("audio/webm", "video/webm", "audio/mp4",
            "video/mp4", "application/mp4", "application/octet-stream");

    private final AudioMetadataInspector metadataInspector;
    private final SpeechToTextProvider provider;

    public SpeechTranscriptionService(AudioMetadataInspector metadataInspector, SpeechToTextProvider provider) {
        this.metadataInspector = metadataInspector;
        this.provider = provider;
    }

    public SpeechTranscriptionResponse transcribe(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw invalidAudio();
        }
        if (audio.getSize() > MAX_FILE_SIZE) {
            throw tooLarge();
        }

        String contentType = normalizeContentType(audio.getContentType());
        if (!contentType.isEmpty() && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw invalidAudio();
        }

        byte[] content = readContent(audio);
        var metadata = metadataInspector.inspect(content);
        if (!isCompatible(contentType, metadata.mediaType()) || metadata.durationSeconds() > MAX_DURATION_SECONDS) {
            throw invalidAudio();
        }

        String transcript = provider.transcribe(new SpeechAudio(content, metadata.mediaType(),
                safeFilename(audio.getOriginalFilename()), metadata.durationSeconds()));
        if (transcript == null || transcript.isBlank()) {
            throw new SpeechTranscriptionException(502, "음성을 텍스트로 변환하지 못했습니다. 다시 시도해 주세요.");
        }
        return new SpeechTranscriptionResponse(transcript.trim());
    }

    private byte[] readContent(MultipartFile audio) {
        try {
            return audio.getBytes();
        } catch (IOException exception) {
            throw new SpeechTranscriptionException(400, "음성 파일을 읽을 수 없습니다.");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private boolean isCompatible(String declaredType, String detectedType) {
        if (declaredType.isEmpty() || "application/octet-stream".equals(declaredType)) {
            return true;
        }
        return declaredType.endsWith("/webm") && detectedType.endsWith("/webm")
                || declaredType.endsWith("/mp4") && detectedType.endsWith("/mp4");
    }

    private String safeFilename(String filename) {
        if (filename == null) {
            return "audio";
        }
        String normalized = filename.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private SpeechTranscriptionException invalidAudio() {
        return new SpeechTranscriptionException(400, "WebM 또는 MP4 형식의 재생 가능한 음성 파일을 전송해 주세요. (최대 60초)");
    }

    private SpeechTranscriptionException tooLarge() {
        return new SpeechTranscriptionException(413, "음성 파일은 최대 10MB까지 전송할 수 있습니다.");
    }
}
