package com.muse.meomuneum.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.recommendation.controller.SpeechTranscriptionController;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionExceptionHandler;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

@WebMvcTest(SpeechTranscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, SpeechTranscriptionExceptionHandler.class})
class SpeechTranscriptionAdviceIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpeechTranscriptionService service;

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 502})
    void prioritizesSpeechTranscriptionErrorContractOverGlobalHandler(int responseStatus) throws Exception {
        when(service.transcribe(any())).thenThrow(new SpeechTranscriptionException(responseStatus, "speech error"));
        var audio = new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});

        mockMvc.perform(multipart("/api/v1/speech-transcriptions").file(audio))
                .andExpect(status().is(responseStatus))
                .andExpect(jsonPath("$.message").value("speech error"));
    }
}
