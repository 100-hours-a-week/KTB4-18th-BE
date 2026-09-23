package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.media.AudioMetadataInspector;
import com.muse.meomuneum.recommendation.provider.SpeechToTextProvider;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

@ExtendWith(MockitoExtension.class)
class SpeechTranscriptionServiceTests {
    @Mock SpeechToTextProvider provider;
    SpeechTranscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SpeechTranscriptionService(new AudioMetadataInspector(), provider);
    }

    @Test
    void returnsTrimmedTranscriptForValidWebm() {
        when(provider.transcribe(any())).thenReturn("  비 올 때 듣기 좋은 노래  ");
        var audio = new MockMultipartFile(
                "audio", "voice.webm", "audio/webm;codecs=opus", AudioMetadataInspectorTests.webm(59.5f));

        var response = service.transcribe(audio);

        assertEquals("비 올 때 듣기 좋은 노래", response.transcript());
        var capturedAudio = ArgumentCaptor.forClass(SpeechAudio.class);
        verify(provider).transcribe(capturedAudio.capture());
        assertEquals("audio/webm", capturedAudio.getValue().mediaType());
        assertEquals(59.5d, capturedAudio.getValue().durationSeconds(), 0.001d);
    }

    @Test
    void rejectsAudioLongerThanSixtySeconds() {
        var audio = new MockMultipartFile(
                "audio", "voice.mp4", "audio/mp4", AudioMetadataInspectorTests.mp4(60_001));

        var exception = assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio));

        assertEquals(400, exception.getStatus());
    }

    @Test
    void rejectsUnsupportedContentType() {
        var audio = new MockMultipartFile(
                "audio", "voice.txt", "text/plain", AudioMetadataInspectorTests.webm(10f));

        var exception = assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio));

        assertEquals(400, exception.getStatus());
    }

    @Test
    void rejectsAudioLargerThanTenMegabytes() {
        MultipartFile audio = org.mockito.Mockito.mock(MultipartFile.class);
        when(audio.isEmpty()).thenReturn(false);
        when(audio.getSize()).thenReturn(10L * 1024 * 1024 + 1);

        var exception = assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio));

        assertEquals(413, exception.getStatus());
    }

    @Test
    void rejectsBlankProviderResponse() {
        when(provider.transcribe(any())).thenReturn("  ");
        var audio = new MockMultipartFile(
                "audio", "voice.webm", "audio/webm", AudioMetadataInspectorTests.webm(10f));

        var exception = assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio));

        assertEquals(502, exception.getStatus());
    }
}
