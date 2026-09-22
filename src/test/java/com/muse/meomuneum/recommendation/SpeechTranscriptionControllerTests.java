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

import com.muse.meomuneum.recommendation.controller.SpeechTranscriptionController;
import com.muse.meomuneum.recommendation.dto.response.SpeechTranscriptionResponse;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionExceptionHandler;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

@ExtendWith(MockitoExtension.class)
class SpeechTranscriptionControllerTests {
    @Mock SpeechTranscriptionService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SpeechTranscriptionController(service))
                .setControllerAdvice(new SpeechTranscriptionExceptionHandler())
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
}
