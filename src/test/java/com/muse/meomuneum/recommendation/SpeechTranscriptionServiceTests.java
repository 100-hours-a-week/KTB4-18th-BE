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
import com.muse.meomuneum.recommendation.media.SpeechAudioProcessor;
import com.muse.meomuneum.recommendation.provider.SpeechToTextProvider;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

@ExtendWith(MockitoExtension.class)
class SpeechTranscriptionServiceTests {
    @Mock
    SpeechToTextProvider provider;
    @Mock
    SpeechAudioProcessor processor;
    SpeechTranscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SpeechTranscriptionService(new AudioMetadataInspector(), provider, processor);
    }

    @Test
    void returnsTrimmedTranscriptForValidWebm() {
        when(provider.transcribe(any())).thenReturn("  비 올 때 듣기 좋은 노래  ");
        when(processor.process(any(), any()))
                .thenAnswer(invocation -> new SpeechAudio(invocation.getArgument(0), "audio/mp4", "audio.mp4", 59.5d));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm;codecs=opus",
                AudioMetadataInspectorTests.webm(59.5f));

        var response = service.transcribe(audio);

        assertEquals("비 올 때 듣기 좋은 노래", response.transcript());
        var capturedAudio = ArgumentCaptor.forClass(SpeechAudio.class);
        verify(provider).transcribe(capturedAudio.capture());
        assertEquals("audio/mp4", capturedAudio.getValue().mediaType());
        assertEquals(59.5d, capturedAudio.getValue().durationSeconds(), 0.001d);
    }

    @Test
    void forwardsPreparedAudioForLongerThanSixtySeconds() {
        byte[] original = AudioMetadataInspectorTests.mp4(60_001);
        var prepared = new SpeechAudio(new byte[]{1, 2}, "audio/mp4", "audio.mp4", 59.9d);
        when(processor.process(original, "audio/mp4")).thenReturn(prepared);
        when(provider.transcribe(prepared)).thenReturn("정상 전사");
        var audio = new MockMultipartFile("audio", "voice.mp4", "audio/mp4", original);

        assertEquals("정상 전사", service.transcribe(audio).transcript());
        verify(provider).transcribe(prepared);
    }

    @Test
    void doesNotCallAiWhenPreparationFails() {
        when(processor.process(any(), any())).thenThrow(new SpeechTranscriptionException(504, "준비 시간 초과"));
        var audio = new MockMultipartFile("audio", "voice.mp4", "audio/mp4", AudioMetadataInspectorTests.mp4(60_001));
        assertEquals(504,
                assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio)).getStatus());
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void rejectsMismatchedMimeBeforePreparation() {
        var audio = new MockMultipartFile("audio", "voice.mp4", "audio/mp4", AudioMetadataInspectorTests.webm(10f));
        assertEquals(400,
                assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio)).getStatus());
        org.mockito.Mockito.verifyNoInteractions(processor, provider);
    }

    @Test
    void rejectsUnsupportedContentType() {
        var audio = new MockMultipartFile("audio", "voice.txt", "text/plain", AudioMetadataInspectorTests.webm(10f));

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
        when(processor.process(any(), any())).thenReturn(new SpeechAudio(new byte[]{1}, "audio/mp4", "audio.mp4", 10d));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", AudioMetadataInspectorTests.webm(10f));

        var exception = assertThrows(SpeechTranscriptionException.class, () -> service.transcribe(audio));

        assertEquals(502, exception.getStatus());
    }
}
