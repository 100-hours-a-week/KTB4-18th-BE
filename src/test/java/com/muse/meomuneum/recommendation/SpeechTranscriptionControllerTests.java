package com.muse.meomuneum.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.recommendation.controller.SpeechTranscriptionController;
import com.muse.meomuneum.recommendation.dto.response.SpeechTranscriptionResponse;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionExceptionHandler;
import com.muse.meomuneum.recommendation.media.AudioMetadataInspector;
import com.muse.meomuneum.recommendation.provider.StubSpeechToTextProvider;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

@ExtendWith(MockitoExtension.class)
class SpeechTranscriptionControllerTests {
    @Mock SpeechTranscriptionService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SpeechTranscriptionController(service))
                .setControllerAdvice(new SpeechTranscriptionExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsTranscript() throws Exception {
        when(service.transcribe(any())).thenReturn(new SpeechTranscriptionResponse("비 올 때 듣기 좋은 노래"));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});

        mvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("speech transcription completed"))
                .andExpect(jsonPath("$.data.transcript").value("비 올 때 듣기 좋은 노래"));
    }

    @Test
    void returnsBadRequestWhenAudioIsMissing() throws Exception {
        mvc.perform(multipart("/api/v1/speech-transcriptions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("음성 파일을 선택해 주세요."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsPayloadTooLargeException() throws Exception {
        when(service.transcribe(any()))
                .thenThrow(new SpeechTranscriptionException(
                        413, "음성 파일은 최대 10MB까지 전송할 수 있습니다."));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});

        mvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message")
                        .value("음성 파일은 최대 10MB까지 전송할 수 있습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsTranscriptionException() throws Exception {
        when(service.transcribe(any())).thenThrow(new SpeechTranscriptionException(502, "speech unavailable"));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});

        mvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("speech unavailable"));
    }

    @Test
    void mapsTranscriptionTimeout() throws Exception {
        when(service.transcribe(any())).thenThrow(new SpeechTranscriptionException(504, "speech timeout"));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});

        mvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.message").value("speech timeout"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        when(service.transcribe(any())).thenThrow(new IllegalStateException("sensitive provider detail"));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});

        mvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("internal server error"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void validatesAudioAndReturnsStubTranscriptThroughHttpContract() throws Exception {
        var integratedService = new SpeechTranscriptionService(
                new AudioMetadataInspector(), new StubSpeechToTextProvider("비 오는 날 드라이브 음악"));
        var integratedMvc = MockMvcBuilders
                .standaloneSetup(new SpeechTranscriptionController(integratedService))
                .setControllerAdvice(new SpeechTranscriptionExceptionHandler(), new GlobalExceptionHandler())
                .build();
        var audio = new MockMultipartFile(
                "audio", "voice.webm", "audio/webm", AudioMetadataInspectorTests.webm(30f));

        integratedMvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transcript").value("비 오는 날 드라이브 음악"));
    }

    @Test
    void rejectsLongAudioThroughHttpContract() throws Exception {
        var integratedService = new SpeechTranscriptionService(
                new AudioMetadataInspector(), new StubSpeechToTextProvider("사용되지 않는 문장"));
        var integratedMvc = MockMvcBuilders
                .standaloneSetup(new SpeechTranscriptionController(integratedService))
                .setControllerAdvice(new SpeechTranscriptionExceptionHandler(), new GlobalExceptionHandler())
                .build();
        var audio = new MockMultipartFile(
                "audio", "voice.mp4", "audio/mp4", AudioMetadataInspectorTests.mp4(60_001));

        integratedMvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
