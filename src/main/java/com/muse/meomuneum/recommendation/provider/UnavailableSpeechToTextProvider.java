package com.muse.meomuneum.recommendation.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;

@Component
@ConditionalOnProperty(prefix = "speech.transcription", name = "provider", havingValue = "unavailable",
        matchIfMissing = true)
public class UnavailableSpeechToTextProvider implements SpeechToTextProvider {
    @Override
    public String transcribe(SpeechAudio audio) {
        throw new SpeechTranscriptionException(502, "음성 변환 서비스를 아직 사용할 수 없습니다.");
    }
}
