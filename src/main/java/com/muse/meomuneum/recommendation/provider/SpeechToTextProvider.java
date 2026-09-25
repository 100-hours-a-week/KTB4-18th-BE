package com.muse.meomuneum.recommendation.provider;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;

public interface SpeechToTextProvider {
    String transcribe(SpeechAudio audio);
}
