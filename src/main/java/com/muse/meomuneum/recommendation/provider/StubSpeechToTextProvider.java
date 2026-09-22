package com.muse.meomuneum.recommendation.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;

/** AI 팀 연동 전 개발·테스트 환경에서만 사용하는 전사 대역입니다. */
@Component
@ConditionalOnProperty(prefix = "speech.transcription", name = "provider", havingValue = "stub")
public class StubSpeechToTextProvider implements SpeechToTextProvider {
    private final String transcript;

    public StubSpeechToTextProvider(
            @Value("${speech.transcription.stub-transcript:"
                    + "비 올 때 듣기 좋은 노래를 추천해줘}") String transcript) {
        this.transcript = transcript;
    }

    @Override
    public String transcribe(SpeechAudio audio) {
        return transcript;
    }
}
